package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Cell-granularity swap between two individual periods.
 *
 * <p>Used by the "single cell" drag mode: the user grabs one period of a
 * schedule (possibly one cell out of a consecutive block) and drops it onto a
 * single target cell. The two cells exchange their course content and each
 * multi-cell source schedule is decomposed around the exchanged cell, so both
 * courses are left contiguous where possible (e.g. dragging the P2 half of a
 * P1-P2 session onto P3 swaps cells P2 and P3 only).
 *
 * @param sourceDay        day of the grabbed cell
 * @param sourcePeriod     period of the grabbed cell
 * @param targetDay        day of the drop cell
 * @param targetPeriod     period of the drop cell
 * @param force            explicitly confirmed despite non-lecturer conflicts
 * @param targetScheduleId the cell occupant the client saw on the drop cell
 *                         (optional hint; the server re-resolves when stale)
 */
public record SwapCellRequest(
        @NotNull UUID scheduleId,
        @NotNull @Positive Integer sourceDay,
        @NotNull @Positive Integer sourcePeriod,
        @NotNull @Positive Integer targetDay,
        @NotNull @Positive Integer targetPeriod,
        boolean force,
        UUID targetScheduleId
) {}