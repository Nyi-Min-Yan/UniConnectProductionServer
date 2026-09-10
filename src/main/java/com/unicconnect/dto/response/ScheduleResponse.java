package com.unicconnect.dto.response;

import com.unicconnect.entity.MeetingType;
import com.unicconnect.entity.ScheduleStatus;
import com.unicconnect.entity.ScheduleType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScheduleResponse(
        UUID scheduleId,
        UUID generationId,
        UUID teachingAssignmentId,
        UUID teachingGroupId,
        String courseCode,
        String courseName,
        String staffName,
        String sectionName,
        Integer semesterNo,
        Integer dayOfWeek,
        UUID startSlotId,
        Integer startPeriodNo,
        String startTime,
        UUID endSlotId,
        Integer endPeriodNo,
        String endTime,
        ScheduleStatus scheduleStatus,
        ScheduleType scheduleType,
        MeetingType meetingType,
        List<String> sections,
        List<String> staffNames,
        Instant createdAt
) {}
