package com.unicconnect.service;

import com.unicconnect.entity.Course;
import com.unicconnect.entity.Major;
import com.unicconnect.entity.Section;
import com.unicconnect.entity.Student;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.repository.MajorRepository;
import com.unicconnect.repository.StudentRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class CurriculumEligibilityService {

    public static final String SHARED_MAJOR_CODE = "CST";

    private static final Set<String> SHARED_AUDIENCE = Set.of("CS", "CST", "CT");

    private final StudentRepository studentRepository;
    private final MajorRepository majorRepository;

    public CurriculumEligibilityService(StudentRepository studentRepository,
                                        MajorRepository majorRepository) {
        this.studentRepository = studentRepository;
        this.majorRepository = majorRepository;
    }

    public String ownerMajorCode(Course course) {
        if (course.getMajor() == null || course.getMajor().getMajorCode() == null) {
            throw new BusinessRuleException("Course " + course.getCourseCode()
                    + " has no major assigned; course ownership is mandatory");
        }
        return course.getMajor().getMajorCode();
    }

    public Set<String> eligibleMajorCodes(Course course) {
        String owner = ownerMajorCode(course);
        if (SHARED_MAJOR_CODE.equals(owner)) {
            return SHARED_AUDIENCE;
        }
        return Set.of(owner);
    }

    /**
     * Cohort majors represented by a delivery section. Derived first from the
     * majors of enrolled students (sections may be mixed-major); when a section
     * has no enrolled students, the section name matching a major_code is used
     * as fallback.
     */
    public Set<String> cohortMajorCodes(Section section) {
        Set<String> cohort = new LinkedHashSet<>();
        for (Student student : studentRepository.findBySection_SectionId(section.getSectionId())) {
            Major major = student.getMajor();
            if (major != null && major.getMajorCode() != null) {
                cohort.add(major.getMajorCode());
            }
        }
        if (cohort.isEmpty() && section.getSectionName() != null) {
            String name = section.getSectionName().trim();
            for (Major major : majorRepository.findAll()) {
                if (major.getMajorCode() != null && major.getMajorCode().equalsIgnoreCase(name)) {
                    cohort.add(major.getMajorCode());
                }
            }
        }
        return cohort;
    }

    /**
     * Structural programme resolution for a section within a given semester.
     *
     * Determined by section identity, NOT by enrolled-student majors.
     *
     * Dedicated sections (name matches a major_code, e.g. "CT"):
     *   → that major's programme exclusively.
     *
     * Shared sections (A/B/C):
     *   Semesters 1–3 → foundational CST programme (all shared courses).
     *   Semesters 4+  → upper-level CS programme (CS courses + shared courses).
     *   These sections never receive CT-only or other dedicated-programme courses.
     */
    public String resolveIntendedProgramme(Section section, int semesterNo) {
        String name = section.getSectionName();
        if (name != null && !name.isBlank()) {
            String trimmed = name.trim();
            for (Major m : majorRepository.findAll()) {
                if (m.getMajorCode() != null && m.getMajorCode().equalsIgnoreCase(trimmed)) {
                    return m.getMajorCode();
                }
            }
        }
        // Non-dedicated sections: academic level determines the programme
        return semesterNo >= 4 ? "CS" : SHARED_MAJOR_CODE;
    }

    /**
     * Structural eligibility: a required course belongs to a section when the
     * course's owning major is compatible with the section's intended
     * programme. Shared/general courses (CST/E/M/P) are compatible with all
     * programmes; major-specific courses (CS/CT) only with their own.
     *
     * Student enrollment data MUST NOT influence this decision.
     */
    public boolean isStructurallyEligible(Course course, Section section, int semesterNo) {
        if (course.getSemester() == null
                || course.getSemester().getSemesterNo() != semesterNo) {
            return false;
        }
        String programme = resolveIntendedProgramme(section, semesterNo);
        if (programme == null) return false;

        String owner = ownerMajorCode(course);

        // Shared/general courses (CST-owned incl. E/M/P) → all programmes
        if (SHARED_MAJOR_CODE.equals(owner)) return true;

        // Major-specific courses → only their own programme's sections
        return owner.equals(programme);
    }

    public boolean isEligibleFor(Course course, String studentMajorCode, UUID semesterId) {
        if (studentMajorCode == null || semesterId == null) {
            return false;
        }
        if (course.getSemester() == null
                || !semesterId.equals(course.getSemester().getSemesterId())) {
            return false;
        }
        return eligibleMajorCodes(course).contains(studentMajorCode);
    }

    /**
     * Structural eligibility: uses semester-based programme resolution.
     * Section identity comes from the university's academic structure,
     * NOT from enrolled-student major distribution.
     */
    public boolean isEligibleForSection(Course course, Section section, UUID semesterId) {
        if (course.getSemester() == null
                || !semesterId.equals(course.getSemester().getSemesterId())) {
            return false;
        }
        int semNo = course.getSemester().getSemesterNo();
        return isStructurallyEligible(course, section, semNo);
    }

    /**
     * A dedicated-cohort section serves exactly one major (e.g. a "CT" section
     * with no enrolled students yet falls back to its name). Curriculum
     * auto-binding and completeness enforcement apply to these sections: a
     * single-major section is the mandatory delivery vehicle for its major's
     * required courses. Mixed-major sections are HOD-managed - their students
     * receive major-specific courses through the dedicated sections.
     */
    public boolean isDedicatedCohortSection(Section section) {
        return cohortMajorCodes(section).size() == 1;
    }

    public boolean isVisibleToStudent(Course course, Student student) {
        if (course.getMajor() == null) {
            return false;
        }
        if (student == null || student.getMajor() == null
                || student.getMajor().getMajorCode() == null) {
            return false;
        }
        if (!eligibleMajorCodes(course).contains(student.getMajor().getMajorCode())) {
            return false;
        }
        if (student.getSemester() != null && course.getSemester() != null) {
            return student.getSemester().getSemesterId()
                    .equals(course.getSemester().getSemesterId());
        }
        return true;
    }

    public boolean canShareDelivery(Course course, Collection<String> majorCodes, UUID semesterId) {
        if (majorCodes == null || majorCodes.isEmpty()) {
            return false;
        }
        if (course.getSemester() == null
                || !semesterId.equals(course.getSemester().getSemesterId())) {
            return false;
        }
        Set<String> eligible = eligibleMajorCodes(course);
        return majorCodes.stream().allMatch(eligible::contains);
    }

    public boolean isRequiredCurriculumCourse(Course course) {
        return course.isRequired();
    }
}
