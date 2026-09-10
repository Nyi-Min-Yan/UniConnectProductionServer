package com.unicconnect.dto.response;

import java.util.UUID;

public record OrganizationalUnitResponse(
        UUID unitId,
        String unitName,
        String unitCode,
        String unitType,
        String description
) {}