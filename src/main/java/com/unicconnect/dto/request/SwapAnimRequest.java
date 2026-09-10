package com.unicconnect.dto.request;

import java.util.UUID;

/**
 * Live undo/redo swap animation broadcast for the shared timetable workspace.
 *
 * <p>When a single HOD (the lock holder) performs an undo/redo of a cell or
 * block swap, the dragging browser publishes the two cells being exchanged so
 * every other connected HOD browser can render the same animated swap overlay
 * while the save request is in flight. The event is purely presentational — no
 * business logic depends on it.
 *
 * @param scheduleId the schedule being undone/redone
 * @param day        the first cell's day (1-5)
 * @param period     the first cell's period (1-6)
 * @param dayTo      the partner cell's day (1-5)
 * @param periodTo   the partner cell's period (1-6)
 */
public record SwapAnimRequest(
        UUID scheduleId,
        Integer day,
        Integer period,
        Integer dayTo,
        Integer periodTo
) {}
