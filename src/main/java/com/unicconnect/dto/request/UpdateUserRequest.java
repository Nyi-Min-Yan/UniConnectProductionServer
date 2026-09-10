package com.unicconnect.dto.request;

import jakarta.validation.constraints.Email;

public record UpdateUserRequest(
        @Email String email,
        Boolean isActive
) {}
