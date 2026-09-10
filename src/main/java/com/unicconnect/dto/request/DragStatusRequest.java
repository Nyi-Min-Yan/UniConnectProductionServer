package com.unicconnect.dto.request;

import java.util.UUID;

/**
 * Live drag/drop broadcast for the shared timetable workspace.
 *
 * <p>The single editor (the HOD currently holding the edit lock) reports every
 * drag gesture: {@code start} when a schedule is picked up, throttled
 * {@code move} events while hovering cells, and {@code end} after a drop or
 * cancel. The lobby realtime stream relays these to every connected member so
 * dragging state and hover positions are visible live on all screens.
 *
 * @param action     one of "start" | "move" | "end"
 * @param scheduleId the schedule being dragged (null for move/end cleanup)
 * @param day        hovered day (1-5) for start/move, null for end
 * @param period     hovered period number for start/move, null for end
 * @param span       how many consecutive cells this drag gesture spans (1 in
 *                   single-cell mode); lets remote viewers render exactly the
 *                   cells being dragged regardless of their own mode
 */
public record DragStatusRequest(
        String action,
        UUID scheduleId,
        Integer day,
        Integer period,
        Integer span
) {}
