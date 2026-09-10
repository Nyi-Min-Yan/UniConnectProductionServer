package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MajorRequest(
        @NotNull UUID unitId,
        @NotBlank String majorCode,
        @NotBlank String majorName
) {}