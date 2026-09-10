package com.unicconnect.service;

import com.unicconnect.dto.request.CreateGenerationRequest;
import com.unicconnect.dto.request.GenerateTimetableRequest;
import com.unicconnect.entity.AcademicTerm;
import com.unicconnect.entity.Semester;
import com.unicconnect.entity.TermStatus;
import com.unicconnect.entity.TimeSlot;
import com.unicconnect.repository.AcademicTermRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Harness for the forward-check vs baseline experiment. Builds ONE snapshot
 * (identical input for both modes), then runs the experimental solver with
 * forward-checking off (baseline control) and on, per semester, same seed.
 * Runs inside a rolled-back transaction; nothing is persisted.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class ForwardCheckExperimentTest {

    private static final UUID SEM1 = UUID.fromString("ca7bb336-9530-4254-bc8d-3e92d3278ab3");
    private static final UUID SEM3 = UUID.fromString("2f4a3bc3-5d7e-44e2-b3ea-3bab943abcfb");
    private static final UUID SEM5 = UUID.fromString("45088805-eefe-47b3-a7d4-eed0c8ec7f0c");
    private static final UUID SEM7 = UUID.fromString("3890917f-e9c5-4ddc-8622-c981820a589f");
    private static final UUID SEC_A = UUID.fromString("81004274-cf05-491f-b1a3-7c8e3d5c77e7");
    private static final UUID SEC_B = UUID.fromString("f19a0bb5-347a-4b40-aa0a-dd7062a0d64e");
    private static final UUID SEC_C = UUID.fromString("adc0d7f4-3075-41d0-9366-c6e8b80f0a27");
    private static final UUID SEC_CT = UUID.fromString("029878c5-d51d-4a0a-8fb1-99642ab4dee1");
    private static final UUID MID_EXAM = UUID.fromString("4f885c79-0eb3-a8b3-d35d-dee741a88907");

    private static final long SEED = 42L;
    private static final long NODE_LIMIT = 4_000_000L;  // generous, bounded (no prod change)
    private static final long TIME_BUDGET_MS = 60_000L;

    @Autowired TimetableGenerationService generationService;
    @Autowired com.unicconnect.repository.UserRepository userRepository;
    @Autowired AcademicTermRepository termRepository;

    private GenerationSnapshot snapshot;
    private int[] startMinute;
    private boolean[] lunchBridge;

    @BeforeEach
    void setUp() {
        UUID userId = userRepository.findByEmail("dawmya@gmail.com").orElseThrow().getUserId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }

    private UUID activeTermId() {
        return termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow().getTermId();
    }

    private void buildSnapshot(List<GenerateTimetableRequest.SemesterSelection> sels) {
        UUID genId = generationService.create(
                new CreateGenerationRequest(activeTermId(), null)).generationId();
        generationService.generate(genId, new GenerateTimetableRequest(MID_EXAM, sels, true));
        snapshot = GenerationSnapshot.findActive(genId).orElseThrow();
        List<TimeSlot> slots = snapshot.getTimeSlots().stream()
                .sorted(Comparator.comparingInt(TimeSlot::getDisplayOrder)).toList();
        int n = slots.size();
        startMinute = new int[n];
        lunchBridge = new boolean[n - 1];
        for (int i = 0; i < n; i++) {
            startMinute[i] = slots.get(i).getStartTime().getHour() * 60 + slots.get(i).getStartTime().getMinute();
            if (i > 0) {
                // lunch bridge between period 3 and 4: production allows P3-P4 gap
                int cp = slots.get(i - 1).getPeriodNo() != null ? slots.get(i - 1).getPeriodNo() : 0;
                int npp = slots.get(i).getPeriodNo() != null ? slots.get(i).getPeriodNo() : 0;
                lunchBridge[i - 1] = (cp == 3 && npp == 4);
            }
        }
    }

    private record Outcome(UUID sem, String label, String strategy, TimetableSchedulingExperiment.Result r) {}

    @Test
    void run_forward_check_experiment() {
        buildSnapshot(List.of(
                new GenerateTimetableRequest.SemesterSelection(SEM1, List.of(SEC_A, SEC_B, SEC_C)),
                new GenerateTimetableRequest.SemesterSelection(SEM3, List.of(SEC_A, SEC_B, SEC_C)),
                new GenerateTimetableRequest.SemesterSelection(SEM5, List.of(SEC_A, SEC_B, SEC_CT)),
                new GenerateTimetableRequest.SemesterSelection(SEM7, List.of(SEC_A, SEC_B, SEC_CT))));

        List<Object[]> rows = new ArrayList<>();
        int idx = 0;
        for (Object[] cfg : List.of(
                new Object[]{SEM1, "Sem-1 A+B+C"},
                new Object[]{SEM3, "Sem-3 A+B+C"},
                new Object[]{SEM5, "Sem-5 A+B+CT"},
                new Object[]{SEM7, "Sem-7 A+B+CT"})) {
            UUID sem = (UUID) cfg[0]; String label = (String) cfg[1];
            GenerationSnapshot.SemesterPartition p = snapshot.getPartition(sem);
            List<TimetableSchedulingExperiment.ExpUnit> units =
                    TimetableSchedulingExperiment.buildUnits(p.getRequirementsByCourse(),
                            p.getMembersByGroup(), p.getSingletons(), sem);

            TimetableSchedulingExperiment.Result base =
                    TimetableSchedulingExperiment.run(units, startMinute, lunchBridge, false, SEED,
                            NODE_LIMIT, TIME_BUDGET_MS, false, label);
            TimetableSchedulingExperiment.Result fc =
                    TimetableSchedulingExperiment.run(units, startMinute, lunchBridge, true, SEED,
                            NODE_LIMIT, TIME_BUDGET_MS, true, label);
            rows.add(new Object[]{label, "BASELINE", base});
            rows.add(new Object[]{label, "FORWARD_CHECK", fc});
            System.out.println("\n==============================================");
            System.out.println("== " + label + "  (" + units.size() + " units)");
            System.out.println("  BASELINE      : " + base.metricsLine());
            System.out.println("  FORWARD_CHECK : " + fc.metricsLine());
            System.out.println("==============================================");
            idx++;
        }

        printTable(rows);

        System.out.println("\n========== SEM-1 FORWARD-CHECK DIAGNOSTICS ==========");
        Object[] row = rows.stream().filter(o -> ((String) o[0]).startsWith("Sem-1") && ((String) o[1]).equals("FORWARD_CHECK"))
                .findFirst().orElse(null);
        if (row != null) {
            TimetableSchedulingExperiment.Result f = (TimetableSchedulingExperiment.Result) row[2];
            System.out.println("  fcRejections      : " + f.fcRejections);
            System.out.println("  zero-domain events: " + f.zeroDomainDetections);
            System.out.println("  first zero-unit   : " + f.fcZeroUnitLabel);
            System.out.println("    course  : " + f.fcZeroCourse);
            System.out.println("    section : " + f.fcZeroSection);
            System.out.println("    2-period: " + f.fcZeroTwoPeriod);
            System.out.println("    combined: " + f.fcZeroCombined);
            System.out.println("    elective: " + f.fcZeroElective);
            System.out.println("  firstBacktrackNode: " + f.firstBacktrackNode);
            System.out.println("  mostBacktracked   : " + f.mostBacktrackedUnit);
            System.out.println("  smallestDomain    : " + f.smallestDomainUnit + " (" + f.maxDepth + " max depth)");
        }
    }

    private void printTable(List<Object[]> rows) {
        System.out.println("\n========== A. BASELINE vs FORWARD-CHECK ==========");
        System.out.println(String.format("%-16s %-14s %-7s %9s %8s %12s %12s %11s %10s",
                "Semester", "Strategy", "Result", "Runtime", "Nodes", "canPlace", "Backtracks", "FC Rejects", "FC Calls"));
        for (Object[] r : rows) {
            TimetableSchedulingExperiment.Result res = (TimetableSchedulingExperiment.Result) r[2];
            System.out.println(String.format("%-16s %-14s %-7s %8dms %8d %12d %11d %10d %10d",
                    r[0], r[1], res.success ? "SOLVED" : "FAIL", res.runtimeMs, res.nodes,
                    res.canPlaceCalls, res.backtracks, res.fcRejections, res.fcCalls));
        }
    }
}
