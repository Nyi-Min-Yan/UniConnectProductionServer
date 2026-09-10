package com.unicconnect.service;

import com.unicconnect.dto.request.CreateGenerationRequest;
import com.unicconnect.dto.request.GenerateTimetableRequest;
import com.unicconnect.entity.AssignmentStatus;
import com.unicconnect.entity.ClassSchedule;
import com.unicconnect.entity.GenerationStatus;
import com.unicconnect.entity.ScheduleType;
import com.unicconnect.entity.TeachingAssignment;
import com.unicconnect.repository.AcademicTermRepository;
import com.unicconnect.repository.ClassScheduleRepository;
import com.unicconnect.repository.TeachingAssignmentRepository;
import com.unicconnect.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Curriculum membership must come from COURSES (semester_id + major eligibility),
 * never from existing TEACHING_ASSIGNMENTS rows. Regression coverage for the
 * CST-2235/Sem4 bug: a required eligible course delivered only to C/CT used to
 * disappear from sections A/B because the solver pool was built exclusively
 * from assignments.
 *
 * Every scenario runs inside the rolled-back test transaction; deliveries that
 * the fix auto-creates are discarded automatically.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class MixedSectionCurriculumCoverageTest {

    private static final UUID TERM_ID = UUID.fromString("6eea6860-074e-4e8a-973d-7a538325bef1");
    private static final UUID SEM4 = UUID.fromString("2cbe4a58-543a-43fa-9cf8-c3dc1be5d749");
    private static final UUID SEC_A  = UUID.fromString("c183c6d5-9810-40c7-8d93-7636c2c295fe");
    private static final UUID SEC_B  = UUID.fromString("c60d8263-b9c3-4d77-8ae6-b603ca93f044");
    private static final UUID SEC_CT = UUID.fromString("ab433047-66c0-42ca-b206-294ec27db8cc");

    /** Every required Semester-4 course except the CT-dedicated CT-2236. */
    private static final Set<String> SEM4_CS_EXPECTED = Set.of(
            "CS-2256", "CST-2212", "CST-2213", "CST-2224", "CST-2235", "CST-2241", "E-2201");

    @Autowired TimetableGenerationService generationService;
    @Autowired TeachingAssignmentRepository assignmentRepository;
    @Autowired ClassScheduleRepository scheduleRepository;
    @Autowired AcademicTermRepository termRepository;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void authenticateAsHod() {
        UUID userId = userRepository.findByEmail("dawmya@gmail.com")
                .orElseThrow(() -> new IllegalStateException("test user missing")).getUserId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }

    private UUID startGen(GenerateTimetableRequest.SemesterSelection selection) {
        UUID gid = generationService.create(new CreateGenerationRequest(TERM_ID, null)).generationId();
        generationService.generate(gid, new GenerateTimetableRequest(null, List.of(selection), true));
        return gid;
    }

    private GenerationStatus run(UUID gid) {
        return generationService.runGenerationBackground(gid).status();
    }

    private GenerateTimetableRequest.SemesterSelection sel(UUID sectionId) {
        return new GenerateTimetableRequest.SemesterSelection(SEM4, List.of(sectionId));
    }

    private Set<String> sectionCodes(UUID gid, UUID sectionId) {
        return scheduleRepository.findByGeneration_GenerationId(gid).stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .filter(s -> s.getTeachingAssignment() != null
                        && sectionId.equals(s.getTeachingAssignment().getSection().getSectionId()))
                .map(ClassScheduleService::courseCodeOf)
                .collect(Collectors.toSet());
    }

    /** Reproduces the pre-fix state: removes a delivery row (plus historical
     * schedules referencing it) inside the transaction; rollback restores. */
    private void deleteDelivery(String courseCode, UUID sectionId) {
        assignmentRepository.findWithDetailsByTermId(TERM_ID).stream()
                .filter(a -> a.getAssignmentStatus() != AssignmentStatus.CANCELLED)
                .filter(a -> a.getCourse().getCourseCode().equals(courseCode))
                .filter(a -> sectionId.equals(a.getSection().getSectionId()))
                .forEach(a -> {
                    scheduleRepository.findBySectionIdWithDetails(sectionId).stream()
                            .filter(s -> s.getTeachingAssignment() != null
                                    && a.getAssignmentId().equals(
                                            s.getTeachingAssignment().getAssignmentId()))
                            .forEach(scheduleRepository::delete);
                    scheduleRepository.flush();
                    assignmentRepository.delete(a);
                    assignmentRepository.flush();
                });
    }

    // ===== EXACT BUG: CST-2235 delivered only to C/CT, missing on A/B =====

    @Test
    void t1_cst2235ReachesMixedSectionsAAndB_withoutPreExistingAssignment() {
        deleteDelivery("CST-2235", SEC_A);
        deleteDelivery("CST-2235", SEC_B);

        UUID gid = startGen(new GenerateTimetableRequest.SemesterSelection(
                SEM4, List.of(SEC_A, SEC_B)));
        assertEquals(GenerationStatus.COMPLETED, run(gid));

        Set<String> a = sectionCodes(gid, SEC_A);
        Set<String> b = sectionCodes(gid, SEC_B);
        assertEquals(SEM4_CS_EXPECTED, a,
                "Section A must receive every eligible required Sem-4 course, incl. CST-2235");
        assertEquals(SEM4_CS_EXPECTED, b,
                "Section B must receive every eligible required Sem-4 course, incl. CST-2235");
        assertFalse(a.contains("CT-2236"), "CT-dedicated course must not cascade into mixed section");
    }

    // ===== CT-owned courses stay on their dedicated section =====

    @Test
    void t2_ctOwnedCourseStaysDedicated_andCsOwnedExcludedFromCt() {
        UUID gidA = startGen(sel(SEC_A));
        assertEquals(GenerationStatus.COMPLETED, run(gidA));
        assertFalse(sectionCodes(gidA, SEC_A).contains("CT-2236"),
                "CT-2236 belongs to the dedicated CT delivery");

        UUID gidCT = startGen(sel(SEC_CT));
        assertEquals(GenerationStatus.COMPLETED, run(gidCT));
        Set<String> ct = sectionCodes(gidCT, SEC_CT);
        assertTrue(ct.contains("CT-2236"));
        assertFalse(ct.contains("CS-2256"), "CS-owned course is not eligible for CT cohort");
    }

    // ===== CS-owned course self-heals when its assignment row disappears =====

    @Test
    void t3_csOwnedCourseSelfHealsWhenAssignmentMissing() {
        deleteDelivery("CS-2256", SEC_B);

        UUID gid = startGen(sel(SEC_B));
        assertEquals(GenerationStatus.COMPLETED, run(gid));
        assertTrue(sectionCodes(gid, SEC_B).contains("CS-2256"),
                "Eligible CS-owned course must be re-bound even without any assignment row");
        assertTrue(SEM4_CS_EXPECTED.equals(sectionCodes(gid, SEC_B)));
    }

    // ===== Semester authority: courses.semester_id, never code prefixes =====

    @Test
    void t4_semesterIsolation_onlySem4CoursesGenerated() {
        UUID gid = startGen(new GenerateTimetableRequest.SemesterSelection(
                SEM4, List.of(SEC_A, SEC_B)));
        assertEquals(GenerationStatus.COMPLETED, run(gid));

        Set<String> union = sectionCodes(gid, SEC_A);
        union.addAll(sectionCodes(gid, SEC_B));
        assertTrue(SEM4_CS_EXPECTED.containsAll(union),
                "No course outside courses.semester_id=Sem4 may enter the generation");
        assertEquals(SEM4_CS_EXPECTED, union);
    }
}

