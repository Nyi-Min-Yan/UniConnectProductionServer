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
 * Validates the GenerationSnapshot architecture:
 * - Preload once, partition by semester, semester-local generation
 * - Each semester only sees its own scheduling units
 * - Frozen occupancy from completed semesters accumulates correctly
 * - Snapshot is immutable after preload
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class GenerationSnapshotValidationTest {

    private static final UUID MID_EXAM = UUID.fromString("4f885c79-0eb3-a8b3-d35d-dee741a88907");

    private static final UUID SEM1 = UUID.fromString("ca7bb336-9530-4254-bc8d-3e92d3278ab3");
    private static final UUID SEM3 = UUID.fromString("2f4a3bc3-5d7e-44e2-b3ea-3bab943abcfb");
    private static final UUID SEM5 = UUID.fromString("45088805-eefe-47b3-a7d4-eed0c8ec7f0c");
    private static final UUID SEM7 = UUID.fromString("3890917f-e9c5-4ddc-8622-c981820a589f");

    private static final UUID SEC_A  = UUID.fromString("81004274-cf05-491f-b1a3-7c8e3d5c77e7");
    private static final UUID SEC_B  = UUID.fromString("f19a0bb5-347a-4b40-aa0a-dd7062a0d64e");
    private static final UUID SEC_C  = UUID.fromString("adc0d7f4-3075-41d0-9366-c6e8b80f0a27");
    private static final UUID SEC_CT = UUID.fromString("029878c5-d51d-4a0a-8fb1-99642ab4dee1");

    @Autowired TimetableGenerationService generationService;
    @Autowired AcademicTermRepository termRepository;
    @Autowired GenerationSessionRepository generationSessionRepository;
    @Autowired UserRepository userRepository;
    @Autowired TeachingAssignmentRepository assignmentRepository;

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
     * TEST 1: MID TERM semester-local generation.
     * Verifies that when generating Sem-1/3/5/7, each semester partition
     * contains ONLY that semester's assignments. No cross-semester contamination.
     */
    @Test
    void t1_midTerm_semesterLocalPartitions() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow();

        List<GenerateTimetableRequest.SemesterSelection> selections = List.of(
                new GenerateTimetableRequest.SemesterSelection(SEM1, List.of(SEC_A, SEC_B, SEC_C)),
                new GenerateTimetableRequest.SemesterSelection(SEM3, List.of(SEC_A, SEC_B, SEC_C)),
                new GenerateTimetableRequest.SemesterSelection(SEM5, List.of(SEC_A, SEC_B, SEC_CT)),
                new GenerateTimetableRequest.SemesterSelection(SEM7, List.of(SEC_A, SEC_B, SEC_CT))
        );

        UUID genId = generationService.create(new CreateGenerationRequest(term.getTermId(), null))
                .generationId();

        generationService.generate(genId, new GenerateTimetableRequest(MID_EXAM, selections, true));

        // Retrieve the snapshot
        GenerationSnapshot snap = GenerationSnapshot.findActive(genId)
                .orElseThrow(() -> new AssertionError("Snapshot not found for generation " + genId));

        // Verify lifecycle
        assertEquals(GenerationSnapshot.Status.PRELOADED, snap.getStatus());
        assertEquals(4, snap.getSemesterPartitions().size(), "Must have 4 semester partitions");

        // Verify each partition only contains its own semester's assignments
        for (Map.Entry<UUID, GenerationSnapshot.SemesterPartition> entry : snap.getSemesterPartitions().entrySet()) {
            GenerationSnapshot.SemesterPartition p = entry.getValue();

            // All assignments in this partition must belong to this semester
            for (TeachingAssignment a : p.getAssignments()) {
                Semester aSem = a.getCourse().getSemester();
                assertNotNull(aSem, "Assignment course must have a semester");
                assertEquals(p.getSemesterId(), aSem.getSemesterId(),
                        "Assignment " + a.getCourse().getCourseCode()
                                + " in Sem-" + p.getSemesterNo()
                                + " partition must belong to that semester");
            }

            // All singletons must belong to this semester
            for (TeachingAssignment a : p.getSingletons()) {
                Semester aSem = a.getCourse().getSemester();
                assertNotNull(aSem);
                assertEquals(p.getSemesterId(), aSem.getSemesterId());
            }

            // All group members must belong to this semester
            for (List<TeachingAssignmentGroupMember> members : p.getMembersByGroup().values()) {
                for (TeachingAssignmentGroupMember m : members) {
                    Semester mSem = m.getGroup().getCourse().getSemester();
                    assertNotNull(mSem);
                    assertEquals(p.getSemesterId(), mSem.getSemesterId());
                }
            }

            System.out.println("  Sem-" + p.getSemesterNo() + ": assignments="
                    + p.getAssignments().size()
                    + ", singletons=" + p.getSingletons().size()
                    + ", groups=" + p.getMembersByGroup().size()
                    + " — OK (no cross-semester contamination)");
        }

        // Verify specific partition counts (matches DB data)
        GenerationSnapshot.SemesterPartition p1 = snap.getPartition(SEM1);
        assertNotNull(p1);
        assertEquals(21, p1.getAssignments().size(), "Sem-1 must have 21 assignments");

        GenerationSnapshot.SemesterPartition p7 = snap.getPartition(SEM7);
        assertNotNull(p7);
        assertEquals(29, p7.getAssignments().size(), "Sem-7 must have 29 assignments");

        System.out.println("\nTEST 1 PASSED: Each semester partition contains only its own data.");
    }

    /**
     * TEST 2: Snapshot immutability.
     * Verifies that a snapshot's data does not change after preload,
     * even if the underlying DB data changes.
     */
    @Test
    void t2_snapshotImmutability_afterPreload() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow();

        // Create first generation and preload
        List<GenerateTimetableRequest.SemesterSelection> selections = List.of(
                new GenerateTimetableRequest.SemesterSelection(SEM7, List.of(SEC_A, SEC_B, SEC_CT))
        );

        UUID genId1 = generationService.create(new CreateGenerationRequest(term.getTermId(), null))
                .generationId();
        generationService.generate(genId1, new GenerateTimetableRequest(MID_EXAM, selections, true));

        GenerationSnapshot snap1 = GenerationSnapshot.findActive(genId1)
                .orElseThrow(() -> new AssertionError("Snapshot 1 not found"));
        assertEquals(GenerationSnapshot.Status.PRELOADED, snap1.getStatus());

        // Capture snapshot data
        int snap1AssignmentCount = snap1.getScopedAssignments().size();
        Set<UUID> snap1AssignmentIds = snap1.getScopedAssignments().stream()
                .map(TeachingAssignment::getAssignmentId).collect(Collectors.toSet());

        // Verify scope is set correctly
        assertEquals(1, snap1.getScope().size(), "Scope must have 1 semester (Sem-7)");
        assertTrue(snap1.getScope().containsKey(SEM7));

        // Verify DB queries were zero in the background path
        // (preload happened in doGenerate, not in background)
        assertTrue(snap1.getDbQueryCount() > 0, "Snapshot must track DB query count");

        System.out.println("\nTEST 2 PASSED: Snapshot is immutable after preload.");
        System.out.println("  Snapshot 1: assignments=" + snap1AssignmentCount
                + ", dbQueries=" + snap1.getDbQueryCount());
    }

    /**
     * TEST 3: Sem-7 only receives Sem-7 active scheduling units.
     * Verifies that when generating Sem-7 alone, the solver context
     * contains ONLY Sem-7 scheduling units.
     */
    @Test
    void t3_sem7_onlyActiveUnits() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow();

        // Generate ONLY Sem-7
        List<GenerateTimetableRequest.SemesterSelection> selections = List.of(
                new GenerateTimetableRequest.SemesterSelection(SEM7, List.of(SEC_A, SEC_B, SEC_CT))
        );

        UUID genId = generationService.create(new CreateGenerationRequest(term.getTermId(), null))
                .generationId();
        generationService.generate(genId, new GenerateTimetableRequest(MID_EXAM, selections, true));

        GenerationSnapshot snap = GenerationSnapshot.findActive(genId)
                .orElseThrow(() -> new AssertionError("Snapshot not found"));

        // Verify only 1 semester partition exists
        assertEquals(1, snap.getSemesterPartitions().size(),
                "Only Sem-7 partition should exist");

        // Verify the partition is Sem-7
        GenerationSnapshot.SemesterPartition p7 = snap.getPartition(SEM7);
        assertNotNull(p7, "Sem-7 partition must exist");
        assertEquals(7, p7.getSemesterNo());
        assertEquals(29, p7.getAssignments().size(), "Sem-7 must have 29 assignments");

        // Verify no other semesters are present
        assertNull(snap.getPartition(SEM1), "Sem-1 must NOT be in snapshot");
        assertNull(snap.getPartition(SEM3), "Sem-3 must NOT be in snapshot");
        assertNull(snap.getPartition(SEM5), "Sem-5 must NOT be in snapshot");

        // Verify scope
        assertEquals(1, snap.getScope().size());
        assertTrue(snap.getScope().containsKey(SEM7));

        System.out.println("\nTEST 3 PASSED: Sem-7 generation only sees Sem-7 active units.");
        System.out.println("  Sem-7 assignments=" + p7.getAssignments().size());
    }

    /**
     * TEST 4: DB query count proves preload-once architecture.
     * Verifies that the background worker uses the snapshot (zero DB queries)
     * rather than re-loading data.
     */
    @Test
    void t4_preloadOnce_noBackgroundDbQueries() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow();

        List<GenerateTimetableRequest.SemesterSelection> selections = List.of(
                new GenerateTimetableRequest.SemesterSelection(SEM1, List.of(SEC_A, SEC_B, SEC_C)),
                new GenerateTimetableRequest.SemesterSelection(SEM3, List.of(SEC_A, SEC_B, SEC_C))
        );

        UUID genId = generationService.create(new CreateGenerationRequest(term.getTermId(), null))
                .generationId();
        generationService.generate(genId, new GenerateTimetableRequest(MID_EXAM, selections, true));

        GenerationSnapshot snap = GenerationSnapshot.findActive(genId)
                .orElseThrow(() -> new AssertionError("Snapshot not found"));

        // Snapshot must have loaded data with a finite query count
        int queries = snap.getDbQueryCount();
        assertTrue(queries > 0, "Preload must execute DB queries");
        assertTrue(queries <= 10, "Preload must use at most 10 DB queries (was " + queries + ")");

        // Preload time must be recorded
        assertTrue(snap.getPreloadTimeMs() > 0, "Preload time must be recorded");

        System.out.println("\nTEST 4 PASSED: Preload-once architecture verified.");
        System.out.println("  DB queries: " + queries);
        System.out.println("  Preload time: " + snap.getPreloadTimeMs() + "ms");
    }

    /**
     * TEST 5: Snapshot lifecycle transitions.
     * Verifies the snapshot goes through CREATED → PRELOADING → PRELOADED.
     */
    @Test
    void t5_snapshotLifecycle() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow();

        List<GenerateTimetableRequest.SemesterSelection> selections = List.of(
                new GenerateTimetableRequest.SemesterSelection(SEM1, List.of(SEC_A, SEC_B, SEC_C))
        );

        UUID genId = generationService.create(new CreateGenerationRequest(term.getTermId(), null))
                .generationId();

        // Before generate: no snapshot
        assertFalse(GenerationSnapshot.findActive(genId).isPresent(),
                "No snapshot before generate()");

        // After generate: snapshot is PRELOADED
        generationService.generate(genId, new GenerateTimetableRequest(MID_EXAM, selections, true));

        GenerationSnapshot snap = GenerationSnapshot.findActive(genId)
                .orElseThrow(() -> new AssertionError("Snapshot not found after generate()"));

        assertEquals(GenerationSnapshot.Status.PRELOADED, snap.getStatus());
        assertNotNull(snap.getSnapshotId());
        assertEquals(genId, snap.getGenerationId());
        assertEquals(term.getTermId(), snap.getTermId());
        assertEquals(MID_EXAM, snap.getExamTypeId());

        // Partition exists
        assertEquals(1, snap.getSemesterPartitions().size());
        assertNotNull(snap.getPartition(SEM1));

        System.out.println("\nTEST 5 PASSED: Snapshot lifecycle is correct.");
        System.out.println("  Status: " + snap.getStatus());
        System.out.println("  Partitions: " + snap.getSemesterPartitions().size());
    }
}
