package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Swap (or move) a schedule within a shared draft generation.
 *
 * <p>{@code targetDay}/{@code targetPeriod} is the cell the dragged schedule is
 * dropped onto. If another (non-cancelled) schedule occupies that cell, the two
 * schedules exchange positions; otherwise the dragged schedule simply moves.
 * {@code force=true} indicates the requesting HOD explicitly confirmed the swap
 * despite detected conflicts.
 */
public record SwapScheduleRequest(
        @NotNull UUID scheduleId,
        @NotNull @Positive Integer targetDay,
        @NotNull @Positive Integer targetPeriod,
        boolean force,
        UUID targetScheduleId
) {}
