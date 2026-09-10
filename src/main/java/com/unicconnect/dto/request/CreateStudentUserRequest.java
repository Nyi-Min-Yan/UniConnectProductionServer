package com.unicconnect.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateStudentUserRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password,
        @NotBlank String studentName,
        @NotBlank String rollNo,
        @NotNull UUID majorId,
        UUID semesterId,
        UUID sectionId,
        UUID termId,
        String phoneNo,
        Integer batchYear,
        String address,
        Boolean isActive
) {}
