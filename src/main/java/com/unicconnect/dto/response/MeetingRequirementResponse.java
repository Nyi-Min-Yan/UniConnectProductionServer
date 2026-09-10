package com.unicconnect.dto.response;

import com.unicconnect.entity.MeetingType;

import java.util.UUID;

public record MeetingRequirementResponse(
        UUID requirementId,
        UUID courseId,
        String courseCode,
        MeetingType meetingType,
        Integer sessionsPerWeek,
        Integer periodsPerSession
) {}