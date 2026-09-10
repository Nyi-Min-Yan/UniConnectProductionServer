package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record StudentRequest(
        @NotNull UUID userId,
        @NotNull UUID majorId,
        UUID semesterId,
        UUID sectionId,
        UUID termId,
        @NotBlank String rollNo,
        @NotBlank String studentName,
        String phoneNo,
        String address,
        Integer batchYear
) {}