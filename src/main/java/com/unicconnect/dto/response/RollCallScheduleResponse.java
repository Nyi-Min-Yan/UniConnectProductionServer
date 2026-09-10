package com.unicconnect.dto.response;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** One published-timetable delivery in a lecturer's roll-call schedule. */
public record RollCallScheduleResponse(
        UUID scheduleId,
        int dayOfWeek,
        String dayName,
        LocalTime startTime,
        LocalTime endTime,
        int periodCount,
        String courseCode,
        String courseName,
        Integer semesterNo,
        List<String> sectionNames,
        boolean sharedDelivery,
        /** Existing session for today, if any (scheduleId + today's date). */
        UUID todaySessionId,
        boolean todaySessionCompleted
) {}
