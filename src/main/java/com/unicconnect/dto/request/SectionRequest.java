package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotBlank;

public record SectionRequest(
        @NotBlank String sectionName
) {}