package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StaffRequest(
        @NotNull UUID userId,
        @NotBlank String staffNo,
        @NotBlank String staffName,
        String phoneNo,
        Integer batchYear,
        String address,
        UUID unitId,
        LocalDate joinedAt,
        LocalDate leftDate,
        List<String> positionNames
) {}