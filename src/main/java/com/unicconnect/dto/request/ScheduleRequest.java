package com.unicconnect.dto.request;

import com.unicconnect.entity.MeetingType;
import com.unicconnect.entity.ScheduleStatus;
import com.unicconnect.entity.ScheduleType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record ScheduleRequest(
        @NotNull UUID generationId,
        UUID teachingAssignmentId,
        UUID teachingGroupId,
        @NotNull @Positive Integer dayOfWeek,
        @NotNull UUID startSlotId,
        @NotNull UUID endSlotId,
        @NotNull ScheduleType scheduleType,
        ScheduleStatus scheduleStatus,
        MeetingType meetingType
) {}