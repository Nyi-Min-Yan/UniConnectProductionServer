package com.unicconnect.service;

import com.unicconnect.dto.request.CreateGenerationRequest;
import com.unicconnect.dto.request.GenerateTimetableRequest;
import com.unicconnect.entity.AssignmentStatus;
import com.unicconnect.entity.GenerationStatus;
import com.unicconnect.entity.ScheduleType;
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

/**
 * FINAL acceptance test for the curriculum source-of-truth rule:
 * expected membership comes from COURSES (semester_id + major eligibility),
 * never from existing TEACHING_ASSIGNMENTS rows.
 *
 * Scenario mandated by the specification:
 *   TERM = FINAL, SEMESTER = 2, SECTIONS = A + B,
 *   with a CST-owned Semester-2 course deliberately stripped of its A/B
 *   deliveries before generation. Generation must rediscover it from the
 *   curriculum, create section-specific bindings, schedule it for both
 *   sections, stay idempotent on a second run, exclude CT-only and
 *   wrong-semester courses, and never duplicate assignments.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class Sem2CurriculumSourceOfTruthTest {

    private static final UUID TERM_ID = UUID.fromString("6eea6860-074e-4e8a-973d-7a538325bef1");
    private static final String FINAL_EXAM_TYPE_ID = "6a3c3800-f6e8-4611-adb3-587826cabf84";
    private static final UUID SEM2 = UUID.fromString("dd481bf1-5b54-4f2e-9168-fbfaa4b4f07a");
    private static final UUID SEC_A = UUID.fromString("c183c6d5-9810-40c7-8d93-7636c2c295fe");
    private static final UUID SEC_B = UUID.fromString("c60d8263-b9c3-4d77-8ae6-b603ca93f044");

    /** All seven required Semester-2 courses (none CT-owned in live data). */
    private static final Set<String> SEM2_EXPECTED = Set.of(
            "CST-1212", "CST-1223", "CST-1234", "CST-1241", "E-1201", "M-1201", "P-1201");
    /** The course whose A/B deliveries this test strips before generating. */
    private static final String STRIPPED = "CST-1223";

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

    private long activeBindingCount(String courseCode, UUID sectionId) {
        return assignmentRepository.findWithDetailsByTermId(TERM_ID).stream()
                .filter(a -> a.getAssignmentStatus() == AssignmentStatus.ACTIVE)
                .filter(a -> a.getCourse().getCourseCode().equals(courseCode))
                .filter(a -> sectionId.equals(a.getSection().getSectionId()))
                .count();
    }

    private Set<String> sectionCodes(UUID gid, UUID sectionId) {
        return scheduleRepository.findByGeneration_GenerationId(gid).stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .filter(s -> s.getTeachingAssignment() != null
                        && sectionId.equals(s.getTeachingAssignment().getSection().getSectionId()))
                .map(ClassScheduleService::courseCodeOf)
                .collect(Collectors.toSet());
    }

    @Test
    void finalSem2_ab_curriculumDiscovered_withoutPreExistingDeliveries() {
        // Strip the chosen eligible course from BOTH selected sections first.
        assertFalse(sectionHasActiveDelivery(STRIPPED, SEC_A));
        deleteDelivery(STRIPPED, SEC_A);
        deleteDelivery(STRIPPED, SEC_B);

        // EXACT UI payload shape: FINAL exam type + semester/section selection;
        // autoBindCurriculum omitted -> backend default TRUE.
        UUID gid = generationService.create(new CreateGenerationRequest(TERM_ID, null)).generationId();
        generationService.generate(gid, new GenerateTimetableRequest(
                UUID.fromString(FINAL_EXAM_TYPE_ID),
                List.of(new GenerateTimetableRequest.SemesterSelection(SEM2, List.of(SEC_A, SEC_B))),
                null));
        GenerationStatus status = generationService.runGenerationBackground(gid).status();
        assertEquals(GenerationStatus.COMPLETED, status);

        Set<String> a = sectionCodes(gid, SEC_A);
        Set<String> b = sectionCodes(gid, SEC_B);
        assertEquals(SEM2_EXPECTED, a, STRIPPED + " must be rediscovered from curriculum for A");
        assertEquals(SEM2_EXPECTED, b, STRIPPED + " must be rediscovered from curriculum for B");
        assertFalse(a.contains("CT-4136"), "wrong-semester/CT-only course must never appear");

        // Bindings created automatically, exactly one each (idempotent shape).
        assertEquals(1, activeBindingCount(STRIPPED, SEC_A));
        assertEquals(1, activeBindingCount(STRIPPED, SEC_B));

        // Idempotency: a second full generation must not duplicate bindings.
        UUID gid2 = generationService.create(new CreateGenerationRequest(TERM_ID, null)).generationId();
        generationService.generate(gid2, new GenerateTimetableRequest(
                UUID.fromString(FINAL_EXAM_TYPE_ID),
                List.of(new GenerateTimetableRequest.SemesterSelection(SEM2, List.of(SEC_A, SEC_B))),
                null));
        assertEquals(GenerationStatus.COMPLETED, generationService.runGenerationBackground(gid2).status());
        assertEquals(1, activeBindingCount(STRIPPED, SEC_A), "second run must reuse, not duplicate");
        assertEquals(1, activeBindingCount(STRIPPED, SEC_B), "second run must reuse, not duplicate");
        assertEquals(SEM2_EXPECTED, sectionCodes(gid2, SEC_A));
        assertEquals(SEM2_EXPECTED, sectionCodes(gid2, SEC_B));
    }

    private boolean sectionHasActiveDelivery(String courseCode, UUID sectionId) {
        return assignmentRepository.findWithDetailsByTermId(TERM_ID).stream()
                .filter(a -> a.getAssignmentStatus() == AssignmentStatus.ACTIVE)
                .filter(a -> a.getCourse().getCourseCode().equals(courseCode))
                .anyMatch(a -> sectionId.equals(a.getSection().getSectionId()));
    }
}
