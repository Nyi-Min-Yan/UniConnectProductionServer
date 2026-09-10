package com.unicconnect.dto.response;

import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

public record MonthlyAttendanceResponse(
        UUID studentId,
        String studentName,
        String rollNo,
        String courseCode,
        String courseName,
        int year,
        int month,
        int scheduledPeriods,
        int attendedPeriods,
        int absentPeriods,
        double attendancePercent,
        List<SessionRow> sessions
) {
    public record SessionRow(UUID sessionId, LocalDate date, int scheduledPeriods,
                             int attendedPeriods, double percent) {}
}

