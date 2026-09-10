package com.unicconnect.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DailyAttendanceResponse(
        UUID sessionId,
        LocalDate sessionDate,
        UUID scheduleId,
        String courseCode,
        String courseName,
        String sectionNames,
        int scheduledPeriods,
        List<SlotDto> slots,
        List<StudentRow> students
) {
    public record SlotDto(int periodNo, String startTime, String endTime) {}
    public record StudentRow(UUID studentId, String rollNo, String studentName,
                             String status, int attendedPeriods,
                             double attendancePercent) {}
}

