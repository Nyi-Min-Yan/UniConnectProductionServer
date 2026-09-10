package com.unicconnect.dto.response;

import java.util.UUID;

public record MajorResponse(
        UUID majorId,
        UUID unitId,
        String unitCode,
        String majorCode,
        String majorName
) {}