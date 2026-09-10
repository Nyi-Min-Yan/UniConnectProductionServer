package com.unicconnect.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateStaffUserRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password,
        @NotBlank String staffName,
        String staffNo,
        @NotNull UUID unitId,
        String phoneNo,
        Integer batchYear,
        String address,
        LocalDate joinedAt,
        @NotEmpty(message = "At least one position is required") List<String> positionNames,
        Boolean isActive
) {}
