package com.unicconnect.dto.response;

import java.util.List;

/**
 * Result of a schedule swap/move request.
 *
 * <ul>
 *   <li>{@code swapped=false} with a non-empty {@code conflicts} list: the swap
 *       was blocked pending explicit HOD confirmation ({@code force=true}).</li>
 *   <li>{@code swapped=true}: the change was applied; {@code schedules} holds the
 *       updated records (two for a swap, one for a plain move).</li>
 * </ul>
 */
public record SwapScheduleResponse(
        boolean swapped,
        List<String> conflicts,
        List<ScheduleResponse> schedules
) {}
