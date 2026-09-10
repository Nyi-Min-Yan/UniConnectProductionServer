package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotBlank;

public record OrganizationalUnitRequest(
        @NotBlank String unitName,
        @NotBlank String unitCode,
        String unitType,
        String description
) {}