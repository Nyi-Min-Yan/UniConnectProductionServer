package com.unicconnect.dto.response;

import com.unicconnect.entity.RegistrationStatus;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID userId,
        String email,
        String roleName,
        boolean isActive,
        RegistrationStatus registrationStatus,
        Instant lastLogin,
        Instant createdAt
) {}