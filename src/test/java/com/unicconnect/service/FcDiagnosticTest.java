package com.unicconnect.service;

import com.unicconnect.dto.request.CreateGenerationRequest;
import com.unicconnect.dto.request.GenerateTimetableRequest;
import com.unicconnect.entity.AcademicTerm;
import com.unicconnect.entity.TermStatus;
import com.unicconnect.entity.TimeSlot;
import com.unicconnect.repository.AcademicTermRepository;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class FcDiagnosticTest {

    private static final UUID SEM1 = UUID.fromString("ca7bb336-9530-4254-bc8d-3e92d3278ab3");
    private static final UUID SEC_A = UUID.fromString("81004274-cf05-491f-b1a3-7c8e3d5c77e7");
    private static final UUID SEC_B = UUID.fromString("f19a0bb5-347a-4b40-aa0a-dd7062a0d64e");
    private static final UUID SEC_C = UUID.fromString("adc0d7f4-3075-41d0-9366-c6e8b80f0a27");
    private static final UUID MID_EXAM = UUID.fromString("4f885c79-0eb3-a8b3-d35d-dee741a88907");

    @Autowired TimetableGenerationService generationService;
    @Autowired UserRepository userRepository;
    @Autowired AcademicTermRepository termRepository;

    private GenerationSnapshot snapshot;

    @BeforeEach
    void auth() {
        UUID uid = userRepository.findByEmail("dawmya@gmail.com").orElseThrow().getUserId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        uid, null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }

    @Test
    void inspectSem1() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst().orElseThrow();
        UUID genId = generationService.create(new CreateGenerationRequest(term.getTermId(), null)).generationId();
        generationService.generate(genId, new GenerateTimetableRequest(MID_EXAM, List.of(
                new GenerateTimetableRequest.SemesterSelection(SEM1, List.of(SEC_A, SEC_B, SEC_C))), true));
        GenerationSnapshot snap = GenerationSnapshot.findActive(genId).orElseThrow();
        snapshot = snap;
        GenerationSnapshot.SemesterPartition p = snap.getPartition(SEM1);

        List<TimeSlot> slots = snap.getTimeSlots().stream()
                .sorted(Comparator.comparingInt(TimeSlot::getDisplayOrder)).toList();
        System.out.println("\n===== TIME SLOTS (count=" + slots.size() + ") =====");
        for (TimeSlot t : slots) {
            System.out.println("  displayOrder=" + t.getDisplayOrder()
                    + " periodNo=" + t.getPeriodNo()
                    + " start=" + t.getStartTime() + " end=" + t.getEndTime());
        }

        List<TimetableSchedulingExperiment.ExpUnit> units =
                TimetableSchedulingExperiment.buildUnits(p.getRequirementsByCourse(),
                        p.getMembersByGroup(), p.getSingletons(), SEM1);
        System.out.println("\n===== SEM-1 UNITS (n=" + units.size() + ") =====");
        for (TimetableSchedulingExperiment.ExpUnit u : units) {
            Set<UUID> staff = u.staffIds;
            System.out.println("  " + u.describe()
                    + "  combined=" + u.combined
                    + " elective=" + u.elective
                    + " staff=" + staff.size()
                    + " sections=" + u.sectionIds.size());
        }

        System.out.println("\n===== ROOT DOMAINS (no placements) =====");
        computeDomains(units, "root");

        System.out.println("\n===== AFTER PLACING ONLY THE 3 COMBINED [2x2] COURSES =====");
        simulateCombinedOnly(units);
    }

    private void computeDomains(List<TimetableSchedulingExperiment.ExpUnit> units, String tag) {
        int[] startMinute = deriveStartMinutes(slots());
        boolean[] lunchBridge = deriveLunchBridge(slots());
        TimetableSchedulingExperiment.Grid g = new TimetableSchedulingExperiment.Grid();
        TimetableSchedulingExperiment.PlacementCounter c = new TimetableSchedulingExperiment.PlacementCounter();
        for (TimetableSchedulingExperiment.ExpUnit u : units) {
            int d = TimetableSchedulingExperiment.domainSize(u, new HashSet<>(), startMinute, lunchBridge, g, c);
            System.out.println("  " + u.describe()
                    + "  -> domain=" + d + "  canPlaceCalls=" + c.calls);
            c.calls = 0;
        }
    }

    private void simulateCombinedOnly(List<TimetableSchedulingExperiment.ExpUnit> units) {
        int[] startMinute = deriveStartMinutes(slots());
        boolean[] lunchBridge = deriveLunchBridge(slots());
        TimetableSchedulingExperiment.Grid g = new TimetableSchedulingExperiment.Grid();
        TimetableSchedulingExperiment.PlacementCounter c = new TimetableSchedulingExperiment.PlacementCounter();
        for (TimetableSchedulingExperiment.ExpUnit u : units) {
            if (!u.combined) continue;
            int placed = 0;
            for (int occ = 1; occ <= u.sessionsPerWeek; occ++) {
                List<TimetableSchedulingExperiment.Placement> opts =
                        TimetableSchedulingExperiment.placementsFor(u,
                                new HashSet<>() /*NOTE: naive; better to track per course*/, startMinute, lunchBridge, g, c);
                System.out.println("    combined " + u.describe() + " occ" + occ + " domain=" + opts.size());
                if (!opts.isEmpty()) {
                    var p = opts.get(0);
                    g.place(u.staffIds, u.sectionIds, u.semesterId, p.day, p.startOrder, p.endOrder,
                            u.electiveGroup, u.ownerKey, occ);
                }
            }
        }
        System.out.println("  After combined-only, remaining section-unit domains:");
        for (TimetableSchedulingExperiment.ExpUnit u : units) {
            if (u.combined) continue;
            int d = TimetableSchedulingExperiment.domainSize(u, new HashSet<>(), startMinute, lunchBridge, g, c);
            System.out.println("    " + u.describe() + " -> domain=" + d + "  canPlaceCalls=" + c.calls);
            c.calls = 0;
        }
    }

    private List<TimeSlot> slots() {
        return snapshot.getTimeSlots().stream()
                .sorted(Comparator.comparingInt(TimeSlot::getDisplayOrder)).toList();
    }

    private static int[] deriveStartMinutes(List<TimeSlot> slots) {
        int[] s = new int[slots.size()];
        for (int i = 0; i < slots.size(); i++)
            s[i] = slots.get(i).getStartTime().getHour() * 60 + slots.get(i).getStartTime().getMinute();
        return s;
    }

    private static boolean[] deriveLunchBridge(List<TimeSlot> slots) {
        boolean[] b = new boolean[slots.size() - 1];
        for (int i = 1; i < slots.size(); i++) {
            Integer cp = slots.get(i - 1).getPeriodNo();
            Integer npp = slots.get(i).getPeriodNo();
            b[i - 1] = (cp != null && npp != null && cp == 3 && npp == 4);
        }
        return b;
    }
}
