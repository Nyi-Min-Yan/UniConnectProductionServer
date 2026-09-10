package com.unicconnect.dto.response;

import java.time.Instant;
import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        UUID userId,
        String email,
        String roleName,
        boolean isActive
) {}