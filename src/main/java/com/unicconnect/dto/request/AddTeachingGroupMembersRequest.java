package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record AddTeachingGroupMembersRequest(
        @NotEmpty List<UUID> assignmentIds
) {}
