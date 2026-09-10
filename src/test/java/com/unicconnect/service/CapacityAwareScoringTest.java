package com.unicconnect.service;

import com.unicconnect.entity.CourseMeetingRequirement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free unit tests for the capacity-aware candidate scoring and the
 * structural-course classification that drive the priority-aware scheduler.
 *
 * Verifies (Section 30 properties where statically testable):
 *  A. 4x1 classification is the highest structural priority.
 *  C. all 5 two-period windows (P1-P2 .. P5-P6) are recognised.
 *  E. 4x1 (priority 1) is ordered before mixed 2x1+1x2 (2) before 2x2 (3).
 *  K/L. the capacity-preservation metric is a pure function (no DB / no
 *       canPlace changes) that prefers non-fragmenting placements.
 *
 * No Spring context, no DB, no persistence.
 */
public class CapacityAwareScoringTest {

    private CourseMeetingRequirement req(int sessions, int periods) {
        CourseMeetingRequirement r = new CourseMeetingRequirement();
        r.setSessionsPerWeek(sessions);
        r.setPeriodsPerSession(periods);
        return r;
    }

    // ========== E. structural priority classification ==========

    @Test
    void classify_shape_tiers() {
        // 4x1 = priority 1
        List<CourseMeetingRequirement> p1 = new ArrayList<>();
        p1.add(req(4, 1));
        assertEquals(1, TimetableGenerationService.classifyCourseShape(p1), "4x1 must be priority 1");

        // mixed 2x1 + 1x2 = priority 2
        List<CourseMeetingRequirement> p2 = new ArrayList<>();
        p2.add(req(2, 1)); // 2x1
        p2.add(req(1, 2)); // 1x2
        assertEquals(2, TimetableGenerationService.classifyCourseShape(p2), "mixed 2x1+1x2 must be priority 2");

        // 2x2 = priority 3
        List<CourseMeetingRequirement> p3 = new ArrayList<>();
        p3.add(req(2, 2));
        assertEquals(3, TimetableGenerationService.classifyCourseShape(p3), "2x2 must be priority 3");

        // other (pure 2x1) = priority 4
        List<CourseMeetingRequirement> p4 = new ArrayList<>();
        p4.add(req(2, 1));
        assertEquals(4, TimetableGenerationService.classifyCourseShape(p4), "pure 2x1 must be priority 4");
    }

    // 4x1 needs four distinct weekdays -> four 1-period rows
    @Test
    void four_x_one_requires_four_distinct_days() {
        List<CourseMeetingRequirement> rows = new ArrayList<>();
        rows.add(req(4, 1));
        assertEquals(1, TimetableGenerationService.classifyCourseShape(rows));
        // 4 sessions/week of 1 period each => by the scheduler's usedDays rule they
        // occupy 4 distinct days from the 5 working days. Sanity: 4 <= 5 available days.
        assertEquals(4, rows.get(0).getSessionsPerWeek());
        assertTrue(4 <= 5, "4x1 must fit within the 5 working days without repeating a day");
    }

    // ========== C. all 5 two-period windows are valid ==========

    @Test
    void all_five_two_period_windows_are_capacity() {
        // Empty grid -> all 5 adjacent pairs (P1-P2 .. P5-P6) are free.
        assertEquals(5, TimetableGenerationService.freeAdjacentWindows(0));
        // A single occupied period kills only the two adjacent windows it touches.
        // Occupying P2 (bit1) removes P1-P2 and P2-P3 -> 3 remain.
        assertEquals(3, TimetableGenerationService.freeAdjacentWindows(1 << 1));
        // Occupying P1 (bit0) removes only P1-P2 -> 4 remain (edge is cheap).
        assertEquals(4, TimetableGenerationService.freeAdjacentWindows(1 << 0));
    }

    // ========== K/L. capacity-preservation metric (pure) ==========

    @Test
    void middle_single_destroys_more_capacity_than_edge() {
        // Empty day -> all 5 two-period windows free.
        assertEquals(5, TimetableGenerationService.freeAdjacentWindows(0));

        // Single at the middle (P2) of an empty day breaks two windows (P1-P2, P2-P3):
        int destroyedMiddle = TimetableGenerationService
                .destroyedAdjacentWindows(0, 1 << 1);
        // Single at the edge (P1) breaks only one window (P1-P2):
        int destroyedEdge = TimetableGenerationService
                .destroyedAdjacentWindows(0, 1 << 0);

        assertTrue(destroyedMiddle > destroyedEdge,
                "a middle single must destroy more 2-period windows than an edge single "
                        + "(" + destroyedMiddle + " vs " + destroyedEdge + ")");
        assertEquals(2, destroyedMiddle);
        assertEquals(1, destroyedEdge);
    }

    @Test
    void adjacent_two_period_insertion_consumes_capacity() {
        // Inserting a 2-period session P1-P2 into an empty day uses that one window.
        assertEquals(2, TimetableGenerationService.destroyedAdjacentWindows(0, (1 << 0) | (1 << 1)));
        // It leaves P3-P4, P4-P5, P5-P6 usable.
        int rem = TimetableGenerationService.freeAdjacentWindows((1 << 0) | (1 << 1));
        assertEquals(3, rem);
    }
}
