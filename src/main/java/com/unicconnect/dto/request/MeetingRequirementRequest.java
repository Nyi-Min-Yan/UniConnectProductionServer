package com.unicconnect.dto.request;

import com.unicconnect.entity.MeetingType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record MeetingRequirementRequest(
        UUID courseId,
        @NotNull MeetingType meetingType,
        @NotNull @Positive Integer sessionsPerWeek,
        @NotNull @Positive Integer periodsPerSession
) {}