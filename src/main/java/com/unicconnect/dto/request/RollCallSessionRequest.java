package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * scheduleId is mandatory and validated against the latest PUBLISHED
 * timetable + lecturer coverage. sessionDate is OPTIONAL: when omitted the
 * session targets today; when provided it must fall on the schedule's
 * timetable weekday (validated server-side against class_schedules.day_of_week).
 */
public record RollCallSessionRequest(
        @NotNull UUID scheduleId,
        LocalDate sessionDate
) {}
