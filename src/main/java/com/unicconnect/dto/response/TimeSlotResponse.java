package com.unicconnect.dto.response;

import java.time.LocalTime;
import java.util.UUID;

public record TimeSlotResponse(
        UUID slotId,
        Integer periodNo,
        LocalTime startTime,
        LocalTime endTime,
        int displayOrder
) {}