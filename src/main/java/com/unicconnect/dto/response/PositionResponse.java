package com.unicconnect.dto.response;

import java.util.UUID;

public record PositionResponse(
        UUID positionId,
        String positionName,
        String description
) {}