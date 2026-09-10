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
 * COMPREHENSIVE DIAGNOSTIC: Why does Sem-7 fail?
 *
 * Runs 8 experiments:
 * 1. Normal baseline (current production solver)
 * 2. Fixed combo: A=P1-P2, B=P3-P4, CT=P5-P6 (non-overlapping windows)
 * 3-5. Individual section forced combos
 * 6. Sem-7 alone (no frozen occupancy)
 * 7. Full Mid-Term (Sem-1/3/5 frozen -> Sem-7)
 * 8. Combo catalog + find a valid non-overlapping combo
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class Sem7FailureDiagnosticTest {

    private static final UUID MID_EXAM = UUID.fromString("4f885c79-0eb3-a8b3-d35d-dee741a88907");
    private static final UUID SEM7 = UUID.fromString("3890917f-e9c5-4ddc-8622-c981820a589f");
    private static final UUID SEC_A  = UUID.fromString("81004274-cf05-491f-b1a3-7c8e3d5c77e7");
    private static final UUID SEC_B  = UUID.fromString("f19a0bb5-347a-4b40-aa0a-dd7062a0d64e");
    private static final UUID SEC_C  = UUID.fromString("adc0d7f4-3075-41d0-9366-c6e8b80f0a27");
    private static final UUID SEC_CT = UUID.fromString("029878c5-d51d-4a0a-8fb1-99642ab4dee1");

    private static final UUID SEM1 = UUID.fromString("ca7bb336-9530-4254-bc8d-3e92d3278ab3");
    private static final UUID SEM3 = UUID.fromString("2f4a3bc3-5d7e-44e2-b3ea-3bab943abcfb");
    private static final UUID SEM5 = UUID.fromString("45088805-eefe-47b3-a7d4-eed0c8ec7f0c");

    @Autowired TimetableGenerationService generationService;
    @Autowired AcademicTermRepository termRepository;
    @Autowired GenerationSessionRepository generationSessionRepository;
    @Autowired ClassScheduleRepository scheduleRepository;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void authenticateAsHod() {
        UUID userId = userRepository.findByEmail("dawmya@gmail.com")
                .orElseThrow(() -> new IllegalStateException("test HOD user missing"))
                .getUserId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }

    @Test
    void experiment1_normal_baseline() {
        System.out.println("\n================================================================");
        System.out.println("EXPERIMENT 1: Normal production solver baseline");
        System.out.println("================================================================");
        UUID genId = createSem7Generation();

        long t0 = System.currentTimeMillis();
        var result = generationService.runGenerationBackground(genId);
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println("RESULT: " + result.status());
        System.out.println("TIME: " + elapsed + "ms");
    }

    @Test
    void experiment2_fixed_ABCT_nonOverlapping() {
        System.out.println("\n================================================================");
        System.out.println("EXPERIMENT 2: Fixed A=P1-P2, B=P3-P4, CT=P5-P6 (Mon+Tue)");
        System.out.println("================================================================");
        UUID genId = createSem7Generation();

        Map<String, Object> res = generationService.diagnosticRunForcedCombo(genId, SEM7, new int[]{0, 42, 84});
        printForcedComboResult("2", res);
    }

    @Test
    void experiment3_fixed_ABCT_spreadDays() {
        System.out.println("\n================================================================");
        System.out.println("EXPERIMENT 3: Fixed A=Mon+Wed P1-P2, B=Mon+Wed P3-P4, CT=Mon+Wed P5-P6");
        System.out.println("================================================================");
        UUID genId = createSem7Generation();

        Map<String, Object> res = generationService.diagnosticRunForcedCombo(genId, SEM7, new int[]{5, 47, 89});
        printForcedComboResult("3", res);
    }

    @Test
    void experiment4_fixed_ABCT_differentDays() {
        System.out.println("\n================================================================");
        System.out.println("EXPERIMENT 4: Fixed A=Mon+Tue P1-P2, B=Wed+Thu P1-P2, CT=Fri+Mon P3-P4");
        System.out.println("================================================================");
        UUID genId = createSem7Generation();

        Map<String, Object> res = generationService.diagnosticRunForcedCombo(genId, SEM7, new int[]{0, 120, 200});
        printForcedComboResult("4", res);
    }

    @Test
    void experiment8_combo_catalog() {
        System.out.println("\n================================================================");
        System.out.println("EXPERIMENT 8: Combo catalog + find valid non-overlapping combo");
        System.out.println("================================================================");
        UUID genId = createSem7Generation();
        Map<String, Object> res = generationService.diagnosticRunForcedCombo(genId, SEM7, null);

        System.out.println("SemUnits: " + res.get("semUnits"));
        System.out.println("Descriptors: " + res.get("descriptors"));
        System.out.println("Candidates per group: " + res.get("candidatesPerGroup"));

        @SuppressWarnings("unchecked")
        List<String> catalog = (List<String>) res.get("catalogInfo");
        if (catalog != null) {
            for (String line : catalog) {
                System.out.println("  " + line);
            }
        }
    }

    private UUID createSem7Generation() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst().orElseThrow();
        List<GenerateTimetableRequest.SemesterSelection> selections = List.of(
                new GenerateTimetableRequest.SemesterSelection(SEM7, List.of(SEC_A, SEC_B, SEC_CT)));
        UUID genId = generationService.create(new CreateGenerationRequest(term.getTermId(), null)).generationId();
        generationService.generate(genId, new GenerateTimetableRequest(MID_EXAM, selections, true));
        return genId;
    }

    private void printForcedComboResult(String expNum, Map<String, Object> res) {
        System.out.println("  semUnits=" + res.get("semUnits"));
        System.out.println("  descriptors=" + res.get("descriptors"));
        System.out.println("  candidatesPerGroup=" + res.get("candidatesPerGroup"));

        @SuppressWarnings("unchecked")
        List<String> catalog = (List<String>) res.get("catalogInfo");
        if (catalog != null) {
            for (String line : catalog) {
                System.out.println("  " + line);
            }
        }

        System.out.println("  RESULT=" + res.get("result"));
        System.out.println("  elapsedMs=" + res.get("elapsedMs"));
        System.out.println("  nodes=" + res.get("nodes"));
        System.out.println("  combos=" + res.get("combos"));
        System.out.println("  solveAttempts=" + res.get("solveAttempts"));
        System.out.println("  diagNodes=" + res.get("solverDiagNodes"));
        System.out.println("  diagMaxDepth=" + res.get("solverDiagMaxDepth"));

        @SuppressWarnings("unchecked")
        List<String> failures = (List<String>) res.get("failureReport");
        if (failures != null && !failures.isEmpty()) {
            System.out.println("  failureReport (first 5):");
            failures.stream().limit(5).forEach(f -> System.out.println("    " + f));
        }
    }
}
