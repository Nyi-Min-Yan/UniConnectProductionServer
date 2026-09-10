package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalTime;

public record TimeSlotRequest(
        @NotNull @Positive Integer periodNo,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @PositiveOrZero Integer displayOrder
) {}