package com.unicconnect.dto.response;

import java.time.LocalDate;
import java.util.UUID;

public record StaffPositionAssignmentResponse(
        UUID positionAssignmentId,
        UUID staffId,
        UUID positionId,
        String positionName,
        LocalDate startDate,
        LocalDate endDate,
        UUID assignedByStaffId
) {}