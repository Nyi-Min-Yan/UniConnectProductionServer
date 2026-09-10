package com.unicconnect.service;

import com.unicconnect.dto.request.CreateGenerationRequest;
import com.unicconnect.dto.request.GenerateTimetableRequest;
import com.unicconnect.entity.*;
import com.unicconnect.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CONTROLLED PROBE: Forces the known-good elective choice (CST-4137 for
 * all three sections A/B/CT) and bypasses elective-combination enumeration.
 * Measures MRV, GVP, canPlace, scoring costs in the production solver.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class SolverDiagnosticsProbeTest {

    private static final UUID MID_EXAM = UUID.fromString("4f885c79-0eb3-a8b3-d35d-dee741a88907");
    private static final UUID SEM7 = UUID.fromString("3890917f-e9c5-4ddc-8622-c981820a589f");
    private static final UUID SEC_A  = UUID.fromString("81004274-cf05-491f-b1a3-7c8e3d5c77e7");
    private static final UUID SEC_B  = UUID.fromString("f19a0bb5-347a-4b40-aa0a-dd7062a0d64e");
    private static final UUID SEC_C  = UUID.fromString("adc0d7f4-3075-41d0-9366-c6e8b80f0a27");
    private static final UUID SEC_CT = UUID.fromString("029878c5-d51d-4a0a-8fb1-99642ab4dee1");

    @Autowired TimetableGenerationService generationService;
    @Autowired AcademicTermRepository termRepository;
    @Autowired GenerationSessionRepository generationSessionRepository;
    @Autowired ClassScheduleRepository scheduleRepository;
    @Autowired UserRepository userRepository;
    @Autowired TeachingAssignmentRepository assignmentRepository;
    @Autowired TeachingAssignmentGroupMemberRepository groupMemberRepository;
    @Autowired CourseMeetingRequirementRepository requirementRepository;
    @Autowired TimeSlotRepository timeSlotRepository;
    @Autowired SemesterRepository semesterRepository;
    @Autowired SectionRepository sectionRepository;

    @BeforeEach
    void authenticateAsHod() {
        UUID userId = userRepository.findByEmail("dawmya@gmail.com")
                .orElseThrow(() -> new IllegalStateException("test HOD user missing"))
                .getUserId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }

    /**
     * PROBE 1: Run the production solver normally on Sem-7 (all elective combos).
     * This captures the baseline diagnostics.
     */
    @Test
    void probe1_baseline_sem7() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow();
        UUID termId = term.getTermId();

        List<GenerateTimetableRequest.SemesterSelection> selections = List.of(
                new GenerateTimetableRequest.SemesterSelection(SEM7, List.of(SEC_A, SEC_B, SEC_CT)));

        UUID genId = generationService.create(new CreateGenerationRequest(termId, null)).generationId();
        generationService.generate(genId, new GenerateTimetableRequest(MID_EXAM, selections, true));

        long t0 = System.currentTimeMillis();
        var result = generationService.runGenerationBackground(genId);
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println("\n========== PROBE 1: BASELINE SEM-7 ==========");
        System.out.println("  status: " + result.status());
        System.out.println("  elapsed: " + elapsed + "ms");
        System.out.println("  NOTE: Full diagnostics logged via SolverDiagnostics above.");
    }

    /**
     * PROBE 2: Run production solver on Sem-7 with ONLY Sem-7 scope.
     * Same as probe1 but verifies the snapshot architecture works.
     */
    @Test
    void probe2_sem7_only_snapshot() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow();

        List<GenerateTimetableRequest.SemesterSelection> selections = List.of(
                new GenerateTimetableRequest.SemesterSelection(SEM7, List.of(SEC_A, SEC_B, SEC_CT)));

        UUID genId = generationService.create(new CreateGenerationRequest(term.getTermId(), null)).generationId();
        generationService.generate(genId, new GenerateTimetableRequest(MID_EXAM, selections, true));

        GenerationSnapshot snap = GenerationSnapshot.findActive(genId).orElseThrow();
        assertEquals(GenerationSnapshot.Status.PRELOADED, snap.getStatus());
        assertEquals(1, snap.getSemesterPartitions().size());
        assertNotNull(snap.getPartition(SEM7));

        System.out.println("\n========== PROBE 2: SNAPSHOT VERIFICATION ==========");
        System.out.println("  snapshot status: " + snap.getStatus());
        System.out.println("  partitions: " + snap.getSemesterPartitions().size());
        System.out.println("  Sem-7 assignments: " + snap.getPartition(SEM7).getAssignments().size());
        System.out.println("  Sem-7 singletons: " + snap.getPartition(SEM7).getSingletons().size());
        System.out.println("  dbQueries: " + snap.getDbQueryCount());
    }

    /**
     * PROBE 3: Diagnose elective group enumeration for Sem-7.
     * Enumerates all patterns and logs raw/filtered candidates per group.
     */
    @Test
    void probe3_electiveGroupDiagnostics() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow();

        List<GenerateTimetableRequest.SemesterSelection> selections = List.of(
                new GenerateTimetableRequest.SemesterSelection(SEM7, List.of(SEC_A, SEC_B, SEC_CT)));

        UUID genId = generationService.create(new CreateGenerationRequest(term.getTermId(), null)).generationId();
        generationService.generate(genId, new GenerateTimetableRequest(MID_EXAM, selections, true));

        GenerationSnapshot snap = GenerationSnapshot.findActive(genId).orElseThrow();
        GenerationSnapshot.SemesterPartition p7 = snap.getPartition(SEM7);

        // Build units for Sem-7
        List<TeachingAssignment> scopedAssign = p7.getAssignments();
        List<TeachingAssignment> singletons = p7.getSingletons();
        Map<UUID, List<TeachingAssignmentGroupMember>> membersByGroup = p7.getMembersByGroup();
        Map<UUID, List<CourseMeetingRequirement>> reqsByCourse = p7.getRequirementsByCourse();
        Set<UUID> groupedIds = p7.getGroupedAssignmentIds();

        // Build scheduling units using the service's internal method (via generation)
        // We'll use the snapshot data directly to understand the elective groups
        System.out.println("\n========== PROBE 3: ELECTIVE GROUP DIAGNOSTICS ==========");
        System.out.println("  Sem-7 total assignments: " + scopedAssign.size());
        System.out.println("  Sem-7 singletons: " + singletons.size());
        System.out.println("  Sem-7 groupedIds: " + groupedIds.size());
        System.out.println("  Sem-7 combine groups: " + membersByGroup.size());

        // Count elective vs required
        long electiveCount = scopedAssign.stream()
                .filter(a -> !a.getCourse().isRequired())
                .count();
        long requiredCount = scopedAssign.stream()
                .filter(a -> a.getCourse().isRequired())
                .count();
        System.out.println("  Elective assignments: " + electiveCount);
        System.out.println("  Required assignments: " + requiredCount);

        // List elective courses
        System.out.println("  Elective courses:");
        scopedAssign.stream()
                .filter(a -> !a.getCourse().isRequired())
                .forEach(a -> System.out.println("    " + a.getCourse().getCourseCode()
                        + " | section=" + a.getSection().getSectionName()
                        + " | staff=" + a.getStaff().getStaffNo()
                        + " | sem=" + a.getCourse().getSemester().getSemesterNo()));

        // Count by section
        System.out.println("  Assignments by section:");
        Map<String, Long> bySection = scopedAssign.stream()
                .collect(Collectors.groupingBy(
                        a -> a.getSection().getSectionName(),
                        Collectors.counting()));
        bySection.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println("    " + e.getKey() + ": " + e.getValue()));

        // Check which sections have which electives
        System.out.println("  Elective sections:");
        scopedAssign.stream()
                .filter(a -> !a.getCourse().isRequired())
                .collect(Collectors.groupingBy(
                        a -> a.getSection().getSectionName(),
                        Collectors.mapping(
                                a -> a.getCourse().getCourseCode(),
                                Collectors.toList())))
                .forEach((sec, codes) -> System.out.println("    " + sec + ": " + codes));
    }

    /**
     * PROBE 4: Run the FULL Mid-Term generation (Sem-1/3/5/7) with diagnostics.
     * Captures per-semester diagnostics for comparison.
     */
    @Test
    void probe4_fullMidTerm_diagnostics() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow();

        List<GenerateTimetableRequest.SemesterSelection> selections = List.of(
                new GenerateTimetableRequest.SemesterSelection(
                        UUID.fromString("ca7bb336-9530-4254-bc8d-3e92d3278ab3"),
                        List.of(SEC_A, SEC_B, SEC_C)),
                new GenerateTimetableRequest.SemesterSelection(
                        UUID.fromString("2f4a3bc3-5d7e-44e2-b3ea-3bab943abcfb"),
                        List.of(SEC_A, SEC_B, SEC_C)),
                new GenerateTimetableRequest.SemesterSelection(
                        UUID.fromString("45088805-eefe-47b3-a7d4-eed0c8ec7f0c"),
                        List.of(SEC_A, SEC_B, SEC_CT)),
                new GenerateTimetableRequest.SemesterSelection(SEM7, List.of(SEC_A, SEC_B, SEC_CT))
        );

        UUID genId = generationService.create(new CreateGenerationRequest(term.getTermId(), null)).generationId();
        generationService.generate(genId, new GenerateTimetableRequest(MID_EXAM, selections, true));

        long t0 = System.currentTimeMillis();
        var result = generationService.runGenerationBackground(genId);
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println("\n========== PROBE 4: FULL MID-TERM DIAGNOSTICS ==========");
        System.out.println("  status: " + result.status());
        System.out.println("  elapsed: " + elapsed + "ms");
        System.out.println("  NOTE: Per-semester diagnostics logged via SolverDiagnostics above.");
    }
}
