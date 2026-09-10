package com.unicconnect.dto.request;

import com.unicconnect.entity.AssignmentStatus;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TeachingAssignmentRequest(
        @NotNull UUID courseId,
        @NotNull UUID staffId,
        @NotNull UUID sectionId,
        @NotNull UUID termId,
        AssignmentStatus assignmentStatus,
        UUID assignedByStaffId
) {}