package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record StaffPositionAssignmentRequest(
        @NotNull UUID positionId,
        @NotNull LocalDate startDate,
        LocalDate endDate,
        UUID assignedByStaffId
) {}