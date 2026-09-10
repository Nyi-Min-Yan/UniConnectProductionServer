package com.unicconnect.service;

import com.unicconnect.entity.Course;
import com.unicconnect.entity.Major;
import com.unicconnect.entity.Section;
import com.unicconnect.entity.Semester;
import com.unicconnect.entity.Student;
import com.unicconnect.repository.MajorRepository;
import com.unicconnect.repository.StudentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurriculumEligibilityServiceTest {

    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final MajorRepository majorRepository = mock(MajorRepository.class);
    private final CurriculumEligibilityService service =
            new CurriculumEligibilityService(studentRepository, majorRepository);

    private Course course(String code, String majorCode, int semesterNo, boolean required) {
        Major major = new Major();
        major.setMajorCode(majorCode);
        Semester semester = new Semester();
        semester.setSemesterId(UUID.nameUUIDFromBytes(("sem-" + semesterNo).getBytes()));
        semester.setSemesterNo(semesterNo);
        Course course = new Course();
        course.setCourseCode(code);
        course.setMajor(major);
        course.setSemester(semester);
        course.setRequired(required);
        return course;
    }

    private UUID semesterId(int semesterNo) {
        return UUID.nameUUIDFromBytes(("sem-" + semesterNo).getBytes());
    }

    private Section section(String name) {
        Section section = new Section();
        section.setSectionId(UUID.nameUUIDFromBytes(("sec-" + name).getBytes()));
        section.setSectionName(name);
        return section;
    }

    private Student student(String majorCode) {
        Major major = new Major();
        major.setMajorCode(majorCode);
        Student student = new Student();
        student.setMajor(major);
        return student;
    }

    // D: CST/shared course eligible for CS, CST and CT
    @Test
    void d_cstCourseEligibleForAllThreeMajors() {
        Course cst = course("CST-1102", "CST", 1, true);
        assertEquals(Set.of("CS", "CST", "CT"), service.eligibleMajorCodes(cst));
        assertTrue(service.isEligibleFor(cst, "CS", semesterId(1)));
        assertTrue(service.isEligibleFor(cst, "CST", semesterId(1)));
        assertTrue(service.isEligibleFor(cst, "CT", semesterId(1)));
    }

    // D: general/shared courses (E/M/P) are CST-owned and therefore shareable
    @Test
    void d_generalCoursesAreCstOwnedAndShareable() {
        for (String code : List.of("E-1101", "M-1101", "P-1101")) {
            Course general = course(code, "CST", 1, true);
            assertEquals("CST", service.ownerMajorCode(general));
            assertEquals(Set.of("CS", "CST", "CT"), service.eligibleMajorCodes(general));
        }
    }

    // E: pairwise sharing CS+CST, CS+CT, CST+CT
    @Test
    void e_pairwiseSharingCombinations() {
        Course cst = course("E-2101", "CST", 3, false);
        assertTrue(service.canShareDelivery(cst, List.of("CS", "CST"), semesterId(3)));
        assertTrue(service.canShareDelivery(cst, List.of("CS", "CT"), semesterId(3)));
        assertTrue(service.canShareDelivery(cst, List.of("CST", "CT"), semesterId(3)));
    }

    // F: triple sharing CS+CST+CT
    @Test
    void f_tripleSharingCombination() {
        Course cst = course("M-1201", "CST", 2, true);
        assertTrue(service.canShareDelivery(cst, List.of("CS", "CST", "CT"), semesterId(2)));
    }

    // G: semester isolation — no cross-semester eligibility or sharing
    @Test
    void g_semesterIsolationPreventsCrossSemesterSharing() {
        Course sem1 = course("CST-1102", "CST", 1, true);
        assertFalse(service.isEligibleFor(sem1, "CS", semesterId(2)));
        assertFalse(service.isEligibleFor(sem1, "CST", semesterId(2)));
        assertFalse(service.canShareDelivery(sem1, List.of("CS", "CT"), semesterId(2)));
        assertFalse(service.canShareDelivery(sem1, List.of("CS", "CST", "CT"), semesterId(4)));
    }

    // H: required vs elective distinction is preserved
    @Test
    void h_requiredVersusElectiveDistinction() {
        Course required = course("CS-2256", "CS", 4, true);
        Course elective = course("CST-4137", "CST", 7, false);
        assertTrue(service.isRequiredCurriculumCourse(required));
        assertFalse(service.isRequiredCurriculumCourse(elective));
        assertTrue(service.isEligibleFor(elective, "CT", semesterId(7)));
    }

    // I: specific-course isolation — CS/CT courses never auto-shared
    @Test
    void i_csAndCtCoursesAreNotAutoShared() {
        Course cs = course("CS-3124", "CS", 5, true);
        assertEquals(Set.of("CS"), service.eligibleMajorCodes(cs));
        assertFalse(service.isEligibleFor(cs, "CT", semesterId(5)));
        assertFalse(service.isEligibleFor(cs, "CST", semesterId(5)));
        assertFalse(service.canShareDelivery(cs, List.of("CS", "CT"), semesterId(5)));
        assertFalse(service.canShareDelivery(cs, List.of("CS", "CST", "CT"), semesterId(5)));

        Course ct = course("CT-2234", "CT", 4, true);
        assertEquals(Set.of("CT"), service.eligibleMajorCodes(ct));
        assertFalse(service.isEligibleFor(ct, "CS", semesterId(4)));
        assertFalse(service.isEligibleFor(ct, "CST", semesterId(4)));
        assertFalse(service.canShareDelivery(ct, List.of("CS", "CT"), semesterId(4)));
        assertFalse(service.canShareDelivery(ct, List.of("CS", "CST", "CT"), semesterId(4)));
    }

    // A: cohort majors derived from enrolled students (mixed-major sections)
    @Test
    void a_cohortDerivedFromEnrolledStudents() {
        Section mixed = section("A");
        when(studentRepository.findBySection_SectionId(mixed.getSectionId()))
                .thenReturn(List.of(student("CS"), student("CST"), student("CT")));
        assertEquals(Set.of("CS", "CST", "CT"), service.cohortMajorCodes(mixed));
    }

    // B: cohort falls back to section name matching a major_code when empty
    @Test
    void b_cohortFallsBackToSectionNameMatch() {
        Section ct = section("CT");
        when(studentRepository.findBySection_SectionId(ct.getSectionId())).thenReturn(List.of());
        Major ctMajor = new Major();
        ctMajor.setMajorCode("CT");
        when(majorRepository.findAll()).thenReturn(List.of(ctMajor));
        assertEquals(Set.of("CT"), service.cohortMajorCodes(ct));

        Section unknown = section("Zeta");
        when(studentRepository.findBySection_SectionId(unknown.getSectionId())).thenReturn(List.of());
        assertEquals(Set.of(), service.cohortMajorCodes(unknown));
    }

    // C: eligibility at section level — structural programme resolution
    @Test
    void c_sectionLevelEligibilityRespectsCohort() {
        Course shared = course("CST-2212", "CST", 4, true);
        Course csOnly = course("CS-2256", "CS", 4, true);
        Course ctOnly = course("CT-2236", "CT", 4, true);

        // CT dedicated section: gets CT + shared, never CS-only
        Section ct = section("CT");
        when(studentRepository.findBySection_SectionId(ct.getSectionId())).thenReturn(List.of());
        Major ctMajor = new Major();
        ctMajor.setMajorCode("CT");
        when(majorRepository.findAll()).thenReturn(List.of(ctMajor));
        assertTrue(service.isEligibleForSection(shared, ct, semesterId(4)));
        assertFalse(service.isEligibleForSection(csOnly, ct, semesterId(4)));
        assertTrue(service.isEligibleForSection(ctOnly, ct, semesterId(4)));

        // Mixed section A (Sem 4): programme resolves to CS
        // → CS + shared courses eligible; CT-only NOT eligible
        Section mixed = section("A");
        when(studentRepository.findBySection_SectionId(mixed.getSectionId()))
                .thenReturn(List.of(student("CS"), student("CST"), student("CT")));
        assertTrue(service.isEligibleForSection(shared, mixed, semesterId(4)));
        assertTrue(service.isEligibleForSection(csOnly, mixed, semesterId(4)));
        assertFalse(service.isEligibleForSection(ctOnly, mixed, semesterId(4)),
                "CT-owned course must NOT be structurally eligible for CS section");

        // Unknown section name: programme still resolves via semester fallback
        Section mystery = section("Omega");
        when(studentRepository.findBySection_SectionId(mystery.getSectionId())).thenReturn(List.of());
        // Semester ≥ 4 → programme=CS → CS+shared eligible; CT-only NOT
        assertTrue(service.isEligibleForSection(shared, mystery, semesterId(4)));
        assertFalse(service.isEligibleForSection(ctOnly, mystery, semesterId(4)));
    }

    // G: section-level semester isolation
    @Test
    void g_sectionLevelSemesterIsolation() {
        Course sem4 = course("CST-2212", "CST", 4, true);
        Section ct = section("CT");
        when(studentRepository.findBySection_SectionId(any())).thenReturn(List.of());
        Major ctMajor = new Major();
        ctMajor.setMajorCode("CT");
        when(majorRepository.findAll()).thenReturn(List.of(ctMajor));
        assertFalse(service.isEligibleForSection(sem4, ct, semesterId(5)));
        assertFalse(service.isEligibleForSection(sem4, ct, semesterId(3)));
    }

    // J: student visibility reuses the same rule
    @Test
    void j_studentVisibilityReusesEligibilityRule() {
        Course shared = course("CST-2235", "CST", 4, true);
        Course csOnly = course("CS-2256", "CS", 4, true);

        Student ctStudent = student("CT");
        ctStudent.setSemester(course("x", "CT", 4, true).getSemester());
        assertTrue(service.isVisibleToStudent(shared, ctStudent));
        assertFalse(service.isVisibleToStudent(csOnly, ctStudent));

        Student otherSemester = student("CT");
        otherSemester.setSemester(course("y", "CT", 5, true).getSemester());
        assertFalse(service.isVisibleToStudent(shared, otherSemester));

        Student noMajor = new Student();
        assertFalse(service.isVisibleToStudent(shared, noMajor));
    }
}
