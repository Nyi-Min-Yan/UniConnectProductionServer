package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UpdateUserRoleRequest(
        @NotNull UUID roleId
) {}