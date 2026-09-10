package com.unicconnect.controller;

import com.unicconnect.dto.request.MarkAttendanceRequest;
import com.unicconnect.dto.request.RollCallSessionRequest;
import com.unicconnect.dto.response.AttendanceResponse;
import com.unicconnect.dto.response.ClassSessionResponse;
import com.unicconnect.dto.response.DailyAttendanceResponse;
import com.unicconnect.dto.response.MonthlyAttendanceResponse;
import com.unicconnect.dto.response.RollCallScheduleResponse;
import com.unicconnect.dto.response.RollCallStudentsResponse;
import com.unicconnect.entity.Staff;
import com.unicconnect.service.AttendanceCalculationService;
import com.unicconnect.service.AttendanceService;
import com.unicconnect.service.RollCallService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Roll Call endpoints. Every mutating/reading method re-checks backend
 * authorization (active LECTURER position) and that schedules belong to the
 * latest PUBLISHED timetable.
 */
@RestController
@RequestMapping("/api/rollcall")
public class RollCallController {

    private final RollCallService rollCallService;
    private final AttendanceService attendanceService;
    private final AttendanceCalculationService calculationService;

    public RollCallController(RollCallService rollCallService,
                              AttendanceService attendanceService,
                              AttendanceCalculationService calculationService) {
        this.rollCallService = rollCallService;
        this.attendanceService = attendanceService;
        this.calculationService = calculationService;
    }

    /** Lecturer's weekly schedule from the latest PUBLISHED timetable. */
    @GetMapping("/my-schedule")
    public ResponseEntity<List<RollCallScheduleResponse>> mySchedule() {
        Staff lecturer = rollCallService.requireLecturer();
        return ResponseEntity.ok(rollCallService.mySchedule(lecturer));
    }

    /** Today's classes + current-class auto selection. */
    @GetMapping("/today")
    public ResponseEntity<List<RollCallScheduleResponse>> today() {
        Staff lecturer = rollCallService.requireLecturer();
        var todayName = java.time.LocalDate.now().getDayOfWeek().name();
        return ResponseEntity.ok(rollCallService.mySchedule(lecturer).stream()
                .filter(r -> r.dayName().equalsIgnoreCase(todayName))
                .toList());
    }

    /** Auto-detected currently-running class (may be null). */
    @GetMapping("/current")
    public ResponseEntity<RollCallScheduleResponse> current() {
        Staff lecturer = rollCallService.requireLecturer();
        return ResponseEntity.ok(rollCallService.currentClass(lecturer));
    }

    /** Create/reuse a CLASS_SESSION for a published schedule (default: today). */
    @PostMapping("/sessions")
    public ResponseEntity<ClassSessionResponse> ensureSession(
            @Valid @RequestBody RollCallSessionRequest request) {
        Staff lecturer = rollCallService.requireLecturer();
        // Response is built inside the service transaction: lazy schedule
        // associations (generation/term, course, section) are resolved while
        // the persistence context is still open.
        return ResponseEntity.ok(rollCallService.ensureTodaySessionResponse(
                request.scheduleId(), lecturer, request.sessionDate()));
    }

    /** Roster + saved attendance for a schedule (optionally today's session). */
    @GetMapping("/students")
    public ResponseEntity<RollCallStudentsResponse> students(
            @RequestParam UUID scheduleId,
            @RequestParam(required = false) UUID sessionId) {
        Staff lecturer = rollCallService.requireLecturer();
        return ResponseEntity.ok(
                rollCallService.students(scheduleId, sessionId, lecturer));
    }

    /**
     * Delete a CLASS_SESSION together with every attendance row of that
     * session. Transactional; the same Roll Call authorization applies
     * (lecturer position + schedule coverage + latest PUBLISHED timetable).
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable UUID sessionId) {
        Staff lecturer = rollCallService.requireLecturer();
        rollCallService.deleteSession(sessionId, lecturer);
        return ResponseEntity.noContent().build();
    }

    /** Submit attendance; delegates to AttendanceService (transactional). */
    @PostMapping("/sessions/{sessionId}/attendance")
    public ResponseEntity<List<AttendanceResponse>> submitAttendance(
            @PathVariable UUID sessionId,
            @Valid @RequestBody MarkAttendanceRequest request) {
        return ResponseEntity.ok(attendanceService.markAttendance(sessionId, request));
    }

    /** Dynamic daily report for one session. */
    @GetMapping("/report/daily/{sessionId}")
    public ResponseEntity<DailyAttendanceResponse> daily(@PathVariable UUID sessionId) {
        rollCallService.requireLecturer();
        return ResponseEntity.ok(calculationService.daily(sessionId));
    }

    /**
     * Roll Call History: previously submitted attendance for ONE lecturer-owned
     * schedule of the latest PUBLISHED timetable. Columns = actual
     * CLASS_SESSIONS between fromDate..toDate (inclusive, ISO yyyy-MM-dd;
     * defaults to the current month). All counts/percentages derived, never
     * stored.
     */
    @GetMapping("/history")
    public ResponseEntity<com.unicconnect.dto.response.RollCallHistoryResponse> historyByCohort(
            @RequestParam String courseCode,
            @RequestParam Integer semesterNo,
            @RequestParam(required = false) String sectionName,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fromDate,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate toDate) {
        return ResponseEntity.ok(rollCallService.historyByCohort(
                courseCode, semesterNo, sectionName, fromDate, toDate));
    }

    /** Valid timetable occurrence dates for a schedule within a date range. */
    @GetMapping("/occurrences")
    public ResponseEntity<List<java.time.LocalDate>> occurrences(
            @RequestParam UUID scheduleId,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fromDate,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate toDate) {
        Staff lecturer = rollCallService.requireLecturer();
        return ResponseEntity.ok(
                rollCallService.occurrences(scheduleId, fromDate, toDate, lecturer));
    }

    /** Dynamic monthly per student (+optional course). */
    @GetMapping("/report/monthly")
    public ResponseEntity<MonthlyAttendanceResponse> monthly(
            @RequestParam UUID studentId,
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) UUID courseId) {
        rollCallService.requireLecturer();
        return ResponseEntity.ok(
                calculationService.monthly(studentId, courseId, year, month));
    }
}
