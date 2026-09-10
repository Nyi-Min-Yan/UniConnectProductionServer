package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateGenerationRequest(
        @NotNull UUID termId,
        UUID generatedByStaffId
) {}