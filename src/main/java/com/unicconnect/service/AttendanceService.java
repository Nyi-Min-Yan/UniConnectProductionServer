package com.unicconnect.service;

import com.unicconnect.dto.request.MarkAttendanceRequest;
import com.unicconnect.dto.request.UpdateAttendanceRequest;
import com.unicconnect.dto.response.AttendanceResponse;
import com.unicconnect.entity.*;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.exception.ValidationException;
import com.unicconnect.repository.AttendanceRepository;
import com.unicconnect.repository.ClassSessionRepository;
import com.unicconnect.repository.StudentRepository;
import com.unicconnect.repository.StaffRepository;
import com.unicconnect.repository.TimeSlotRepository;
import com.unicconnect.util.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Roll-call attendance decisions + the student's ACTUAL attended period range
 * (attendance_start_slot_id .. attendance_end_slot_id). Percentages are NEVER
 * stored here; they are derived by {@link AttendanceCalculationService}.
 */
@Service
@Transactional(readOnly = true)
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final ClassSessionRepository sessionRepository;
    private final StudentRepository studentRepository;
    private final StaffRepository staffRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final SecurityUtil securityUtil;
    private final RollCallService rollCallService;

    public AttendanceService(AttendanceRepository attendanceRepository,
                             ClassSessionRepository sessionRepository,
                             StudentRepository studentRepository,
                             StaffRepository staffRepository,
                             TimeSlotRepository timeSlotRepository,
                             SecurityUtil securityUtil,
                             RollCallService rollCallService) {
        this.attendanceRepository = attendanceRepository;
        this.sessionRepository = sessionRepository;
        this.studentRepository = studentRepository;
        this.staffRepository = staffRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.securityUtil = securityUtil;
        this.rollCallService = rollCallService;
    }

    /** Attended periods derived from display order; never persisted. */
    public static int attendedPeriods(TimeSlot start, TimeSlot end) {
        if (start == null || end == null) return 0;
        return end.getDisplayOrder() - start.getDisplayOrder() + 1;
    }

    public List<AttendanceResponse> getAll(UUID sessionId) {
        if (sessionId == null) {
            throw new ValidationException("sessionId query parameter is required");
        }
        List<Attendance> records = attendanceRepository.findBySession_SessionId(sessionId);
        return records.stream().map(this::toResponse).toList();
    }

    public AttendanceResponse getById(UUID attendanceId) {
        return toResponse(findAttendance(attendanceId));
    }

    @Transactional
    public List<AttendanceResponse> markAttendance(UUID sessionId, MarkAttendanceRequest request) {
        ClassSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Class session not found"));
        if (session.getSessionStatus() == SessionStatus.COMPLETED) {
            throw new BusinessRuleException("Attendance cannot be modified after the session is completed");
        }

        // Backend authorization: only the assigned lecturer (an active
        // LECTURER position holder covered by the schedule) may mark.
        Staff staff = staffRepository.findByUser_UserId(securityUtil.currentUserId())
                .orElseThrow(() -> new BusinessRuleException("Only staff can perform roll call"));
        rollCallService.authorizeLecturerForSchedule(staff, session.getSchedule());

        // Scheduled-date rule: attendance belongs to CLASS_SESSION.session_date
        // (the timetable occurrence), which may be today, a PREVIOUS scheduled
        // class being entered late, or an UPCOMING one prepared in advance.
        // Authorization above already restricts this to lecturer-owned
        // schedules of the latest PUBLISHED timetable; only COMPLETED
        // sessions stay locked (checked before this point).

        List<AttendanceResponse> saved = new ArrayList<>();
        for (MarkAttendanceRequest.AttendanceEntry entry : request.entries()) {
            Student student = studentRepository.findById(entry.studentId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Student not found: " + entry.studentId()));

            // AUTHORITATIVE COHORT enforcement: a submission may only target
            // students whose semester == the schedule course's semester AND
            // whose section is covered by this schedule. Prevents cross-
            // semester contamination regardless of what the client sends.
            validateCohortMembership(session.getSchedule(), student);

            TimeSlot[] range = resolveRange(session.getSchedule(),
                    entry.attendanceStatus(), entry.attendanceStartSlotId(),
                    entry.attendanceEndSlotId(), student.getStudentName());

            // Upsert: one decision per (session, student), enforced also by
            // uq_attendance_session_student.
            Attendance attendance = attendanceRepository
                    .findBySession_SessionId(sessionId).stream()
                    .filter(a -> a.getStudent().getStudentId().equals(entry.studentId()))
                    .findFirst().orElse(null);

            if (attendance == null) {
                attendance = new Attendance();
                attendance.setSession(session);
                attendance.setStudent(student);
            }
            attendance.setAttendanceStatus(entry.attendanceStatus());
            attendance.setRemark(entry.remark());
            attendance.setMarkedByStaff(staff);
            attendance.setAttendanceStartSlot(range[0]);
            attendance.setAttendanceEndSlot(range[1]);
            attendance = attendanceRepository.save(attendance);
            saved.add(toResponse(attendance));
        }
        return saved;
    }

    @Transactional
    public AttendanceResponse update(UUID attendanceId, UpdateAttendanceRequest request) {
        Attendance attendance = findAttendance(attendanceId);
        Staff staff = staffRepository.findByUser_UserId(securityUtil.currentUserId())
                .orElseThrow(() -> new BusinessRuleException("Only staff can perform roll call"));
        rollCallService.authorizeLecturerForSchedule(staff, attendance.getSession().getSchedule());
        if (attendance.getSession().getSessionStatus() == SessionStatus.COMPLETED) {
            throw new BusinessRuleException("Attendance cannot be modified after the session is completed");
        }
        TimeSlot[] range = resolveRange(attendance.getSession().getSchedule(),
                request.attendanceStatus(), request.attendanceStartSlotId(),
                request.attendanceEndSlotId(), attendance.getStudent().getStudentName());
        attendance.setAttendanceStatus(request.attendanceStatus());
        attendance.setRemark(request.remark());
        attendance.setAttendanceStartSlot(range[0]);
        attendance.setAttendanceEndSlot(range[1]);
        return toResponse(attendanceRepository.save(attendance));
    }

    @Transactional
    public void delete(UUID attendanceId) {
        Attendance attendance = findAttendance(attendanceId);
        if (attendance.getSession().getSessionStatus() == SessionStatus.COMPLETED) {
            throw new BusinessRuleException("Attendance cannot be deleted after the session is completed");
        }
        attendanceRepository.deleteById(attendanceId);
    }

    /**
     * Cohort rule: student.semester_id == course.semester_id AND
     * student.section_id ∈ schedule coverage. Anything else is rejected.
     */
    private void validateCohortMembership(ClassSchedule schedule, Student student) {
        var course = RollCallService.courseOfRow(schedule);
        UUID courseSem = course != null && course.getSemester() != null
                ? course.getSemester().getSemesterId() : null;
        var covered = ClassScheduleService.coveredSections(schedule);
        boolean semOk = courseSem != null && student.getSemester() != null
                && courseSem.equals(student.getSemester().getSemesterId());
        boolean secOk = student.getSection() != null
                && covered.contains(student.getSection().getSectionId());
        if (!semOk || !secOk) {
            throw new BusinessRuleException(
                    "Student " + student.getRollNo() + " (" + student.getStudentName()
                            + ") is not part of this class cohort"
                            + (!semOk ? " — semester mismatch" : "")
                            + (!secOk ? " — section not covered by this schedule" : ""));
        }
    }

    /**
     * Validates the requested actual period range against the schedule span
     * using real display-order values (never UUID comparison):
     *   PRESENT => both ids required, inside [schedule.start..schedule.end],
     *              start.displayOrder <= end.displayOrder.
     *   ABSENT  => both must be NULL.
     * Returns {startSlot, endSlot} or {null, null} for ABSENT.
     */
    private TimeSlot[] resolveRange(ClassSchedule schedule, AttendanceStatus status,
                                    UUID startSlotId, UUID endSlotId, String who) {
        int lo = Math.min(schedule.getStartSlot().getDisplayOrder(),
                schedule.getEndSlot().getDisplayOrder());
        int hi = Math.max(schedule.getStartSlot().getDisplayOrder(),
                schedule.getEndSlot().getDisplayOrder());

        if (status == AttendanceStatus.ABSENT) {
            if (startSlotId != null || endSlotId != null) {
                throw new ValidationException(
                        "ABSENT students cannot have attended periods" + suffix(who));
            }
            return new TimeSlot[]{null, null};
        }

        if (status == AttendanceStatus.PRESENT && (startSlotId == null || endSlotId == null)) {
            throw new ValidationException(
                    "Select at least one attended period for " + who
                            + " (or mark the student ABSENT)");
        }

        TimeSlot start = timeSlotRepository.findById(startSlotId)
                .orElseThrow(() -> new ValidationException(
                        "Unknown attendance start slot for " + who));
        TimeSlot end = timeSlotRepository.findById(endSlotId)
                .orElseThrow(() -> new ValidationException(
                        "Unknown attendance end slot for " + who));

        if (start.getDisplayOrder() > end.getDisplayOrder()) {
            throw new ValidationException(
                    "Attendance start period cannot be after the end period for " + who);
        }
        if (start.getDisplayOrder() < lo || end.getDisplayOrder() > hi
                || end.getDisplayOrder() < lo || start.getDisplayOrder() > hi) {
            throw new ValidationException(
                    "Selected period is outside the scheduled range for " + who);
        }
        return new TimeSlot[]{start, end};
    }

    private static String suffix(String name) {
        return name != null && !name.isBlank() ? ": " + name : "";
    }

    public Attendance findAttendance(UUID attendanceId) {
        return attendanceRepository.findById(attendanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record not found"));
    }

    public AttendanceResponse toResponse(Attendance attendance) {
        boolean present = attendance.getAttendanceStatus() == AttendanceStatus.PRESENT;
        return new AttendanceResponse(
                attendance.getAttendanceId(),
                attendance.getSession().getSessionId(),
                attendance.getStudent().getStudentId(),
                attendance.getStudent().getRollNo(),
                attendance.getStudent().getStudentName(),
                attendance.getAttendanceStatus(),
                attendance.getRemark(),
                attendance.getMarkedAt(),
                attendance.getMarkedByStaff() != null ? attendance.getMarkedByStaff().getStaffId() : null,
                present ? attendedPeriods(
                        attendance.getAttendanceStartSlot(),
                        attendance.getAttendanceEndSlot()) : 0,
                attendance.getAttendanceStartSlot() != null
                        ? attendance.getAttendanceStartSlot().getSlotId() : null,
                attendance.getAttendanceEndSlot() != null
                        ? attendance.getAttendanceEndSlot().getSlotId() : null);
    }
}
