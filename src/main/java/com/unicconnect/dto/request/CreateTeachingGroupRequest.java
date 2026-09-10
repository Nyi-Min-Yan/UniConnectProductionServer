package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateTeachingGroupRequest(
        @NotNull UUID termId,
        @NotNull UUID courseId,
        @NotEmpty List<UUID> assignmentIds
) {}
