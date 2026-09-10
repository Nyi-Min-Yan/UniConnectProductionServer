package com.unicconnect.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Student-facing roll call summary for the active academic term.
 *
 * <p>Per-course attendance math is computed on the <em>marked</em> classes only
 * (sessions on or before today that have a recorded attendance for this student):
 * <ul>
 *   <li>attendancePct = presentClasses / (presentClasses + absentClasses) * 100</li>
 *   <li>belowThreshold = classes were marked and attendancePct &lt; 75</li>
 *   <li>classesElapsed = occurrences whose date &le; today (planned horizon)</li>
 *   <li>classesHeld    = occurrences &le; today that actually ran (session exists)</li>
 *   <li>unmarkedClasses = classesHeld - (presentClasses + absentClasses)</li>
 *   <li>canMissMore   = max(0, floor(0.25 * classesPlanned) - absentClasses) — absences
 *       still allowed across the whole term without dropping below 75%</li>
 *   <li>needToAttend  = ceil(max(0, 0.75 * marked - presentClasses) / 0.25) — consecutive
 *       fully-attended classes required to recover to at least 75% on the marked horizon</li>
 * </ul>
 */
public record StudentRollCallResponse(
        UUID studentId,
        String rollNo,
        String studentName,
        String semesterNo,
        String sectionName,
        LocalDate termStart,
        LocalDate termEnd,
        List<Course> courses
) {

    public record Course(
            String courseCode,
            String courseName,
            Integer semesterNo,
            List<String> sections,
            List<String> staffNames,
            int classesPlanned,
            int classesElapsed,
            int classesHeld,
            int presentClasses,
            int absentClasses,
            int unmarkedClasses,
            double attendancePct,
            boolean belowThreshold,
            int canMissMore,
            int needToAttend,
            boolean todayClass,
            String todayStartTime,
            String todayEndTime,
            String todayStatus,
            List<Session> sessions
    ) {}

    public record Session(
            UUID sessionId,
            UUID scheduleId,
            LocalDate sessionDate,
            String dayName,
            String startTime,
            String endTime,
            Integer scheduledPeriods,
            String phase,
            String status,
            Integer attendedPeriods,
            String remark,
            String markedByStaffName
    ) {}
}