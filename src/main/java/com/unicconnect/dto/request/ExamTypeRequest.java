package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ExamTypeRequest(
        @NotBlank String examTypeName
) {}