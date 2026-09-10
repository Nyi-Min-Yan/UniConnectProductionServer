package com.unicconnect.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Roll Call History for a logical cohort (course + semester + section).
 * One column per ACTUAL CLASS_SESSION with submitted attendance.
 * All counts and percentages are derived — nothing is ever persisted.
 */
public record RollCallHistoryResponse(
        CohortInfo schedule,
        List<SessionColumn> sessions,
        List<StudentRow> students
) {

    /** Cohort-level identity: course + semester + section coverage. */
    public record CohortInfo(
            UUID courseId,
            String courseCode,
            String courseName,
            Integer semesterNo,
            List<String> sectionNames,
            boolean sharedDelivery,
            List<SlotTime> slots
    ) {}

    /** Timetable slot times for the selected schedule(s). */
    public record SlotTime(UUID slotId, String startTime, String endTime) {}

    /** One actual CLASS_SESSION with submitted attendance = one table column. */
    public record SessionColumn(
            UUID sessionId,
            LocalDate sessionDate,
            String dayOfWeek,
            String startTime,
            String endTime,
            int scheduledPeriods,
            UUID scheduleId
    ) {}

    public record Cell(
            UUID attendanceId,
            UUID sessionId,
            String status,                    // null => not yet marked
            int attendedPeriods,
            int scheduledPeriods,
            UUID attendanceStartSlotId,
            UUID attendanceEndSlotId,
            String remark,
            String markedByStaffName
    ) {}

    public record StudentRow(
            UUID studentId,
            String rollNo,
            String studentName,
            List<Cell> attendance,            // aligned with sessions[] order
            int totalScheduledPeriods,
            int totalAttendedPeriods,
            double attendancePercentage
    ) {}
}
