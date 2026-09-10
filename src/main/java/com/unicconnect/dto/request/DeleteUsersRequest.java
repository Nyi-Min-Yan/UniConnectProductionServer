package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record DeleteUsersRequest(
        @NotEmpty(message = "At least one user must be selected") List<UUID> userIds
) {}
