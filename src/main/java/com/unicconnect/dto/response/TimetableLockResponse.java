package com.unicconnect.dto.response;

import java.time.Instant;
import java.util.UUID;

public record TimetableLockResponse(
        UUID generationId,
        boolean locked,
        UUID staffId,
        String staffName,
        Instant expiresAt
) {
    public static TimetableLockResponse free(UUID generationId) {
        return new TimetableLockResponse(generationId, false, null, null, null);
    }
}
