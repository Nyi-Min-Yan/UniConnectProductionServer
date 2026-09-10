package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public record CreateResultBatchRequest(
        @NotNull UUID termId,
        @NotNull UUID examTypeId,
        @NotNull UUID semesterId,
        @NotNull UUID uploadedByStaffId,
        String uploadedType,
        String sourceFileName,
        @PositiveOrZero Integer totalFiles,
        @PositiveOrZero Integer matchedFiles,
        @PositiveOrZero Integer failedFiles
) {}