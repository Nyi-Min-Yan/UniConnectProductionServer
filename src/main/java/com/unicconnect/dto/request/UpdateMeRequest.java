package com.unicconnect.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateMeRequest(
        @Email String email,
        @Size(min = 8, message = "Password must be at least 8 characters") String currentPassword,
        @Size(min = 8, message = "Password must be at least 8 characters") String newPassword
) {}