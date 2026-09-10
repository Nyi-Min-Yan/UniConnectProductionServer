package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public record CourseRequest(
        @NotNull UUID unitId,
        @NotBlank String courseCode,
        @NotBlank String courseName,
        @NotNull @Positive Integer creditUnit,
        UUID majorId,
        UUID semesterId,
        Boolean isRequired,
        @PositiveOrZero Integer displayOrder
) {}