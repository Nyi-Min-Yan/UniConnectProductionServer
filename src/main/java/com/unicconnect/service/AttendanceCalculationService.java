package com.unicconnect.service;

import com.unicconnect.dto.response.DailyAttendanceResponse;
import com.unicconnect.dto.response.MonthlyAttendanceResponse;
import com.unicconnect.entity.Attendance;
import com.unicconnect.entity.AttendanceStatus;
import com.unicconnect.entity.ClassSchedule;
import com.unicconnect.entity.ClassSession;
import com.unicconnect.entity.Student;
import com.unicconnect.entity.TimeSlot;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.AttendanceRepository;
import com.unicconnect.repository.ClassScheduleRepository;
import com.unicconnect.repository.ClassSessionRepository;
import com.unicconnect.repository.StudentRepository;
import com.unicconnect.repository.TimeSlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic attendance calculations. Percentages are derived from actual
 * CLASS_SESSIONS x schedule slot spans x the ATTENDANCE actual range
 * (attendance_start_slot_id .. attendance_end_slot_id) and are never
 * persisted.
 *
 * scheduledPeriods for a session = (end_slot displayOrder - start_slot
 * displayOrder) + 1.
 *
 * attendedPeriods = 0 for ABSENT, otherwise end - start + 1 on the stored
 * range. Days without a CLASS_SESSION contribute nothing to any denominator.
 */
@Service
@Transactional(readOnly = true)
public class AttendanceCalculationService {

    private final ClassSessionRepository sessionRepository;
    private final ClassScheduleRepository scheduleRepository;
    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final TimeSlotRepository timeSlotRepository;

    public AttendanceCalculationService(ClassSessionRepository sessionRepository,
                                        ClassScheduleRepository scheduleRepository,
                                        AttendanceRepository attendanceRepository,
                                        StudentRepository studentRepository,
                                        TimeSlotRepository timeSlotRepository) {
        this.sessionRepository = sessionRepository;
        this.scheduleRepository = scheduleRepository;
        this.attendanceRepository = attendanceRepository;
        this.studentRepository = studentRepository;
        this.timeSlotRepository = timeSlotRepository;
    }

    public static int spanPeriods(ClassSchedule s) {
        return s.getEndSlot().getDisplayOrder() - s.getStartSlot().getDisplayOrder() + 1;
    }

    // ========== daily ==========

    public DailyAttendanceResponse daily(UUID sessionId) {
        ClassSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Class session not found"));
        ClassSchedule schedule = session.getSchedule();
        int scheduled = spanPeriods(schedule);

        List<DailyAttendanceResponse.SlotDto> slots = new ArrayList<>();
        int lo = Math.min(schedule.getStartSlot().getDisplayOrder(),
                schedule.getEndSlot().getDisplayOrder());
        int hi = Math.max(schedule.getStartSlot().getDisplayOrder(),
                schedule.getEndSlot().getDisplayOrder());
        List<TimeSlot> all = timeSlotRepository.findAll().stream()
                .sorted(Comparator.comparing(TimeSlot::getDisplayOrder)).toList();
        for (TimeSlot t : all) {
            if (t.getDisplayOrder() >= lo && t.getDisplayOrder() <= hi) {
                slots.add(new DailyAttendanceResponse.SlotDto(
                        t.getPeriodNo(), t.getStartTime().toString(), t.getEndTime().toString()));
            }
        }

        Map<UUID, Integer> attendedByStudent = new HashMap<>();
        for (Attendance a : attendanceRepository.findBySession_SessionId(sessionId)) {
            int att = a.getAttendanceStatus() == AttendanceStatus.PRESENT
                    ? AttendanceService.attendedPeriods(
                            a.getAttendanceStartSlot(), a.getAttendanceEndSlot())
                    : 0;
            attendedByStudent.merge(a.getStudent().getStudentId(), att, Integer::sum);
        }

        // AUTHORITATIVE COHORT: course.semester + covered sections — same rule
        // as the roster endpoint, so reports can never mix semester cohorts.
        var course = RollCallService.courseOfRow(schedule);
        if (course == null || course.getSemester() == null) {
            throw new ResourceNotFoundException("Schedule course/semester could not be resolved");
        }
        Map<UUID, Student> roster = new LinkedHashMap<>();
        for (Student st : studentRepository.findBySection_SectionIdInAndSemester_SemesterId(
                List.copyOf(ClassScheduleService.coveredSections(schedule)),
                course.getSemester().getSemesterId())) {
            roster.putIfAbsent(st.getStudentId(), st);
        }
        List<DailyAttendanceResponse.StudentRow> rows = new ArrayList<>();
        for (Student st : roster.values().stream()
                .sorted(Comparator.comparing(Student::getRollNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList()) {
            int attended = attendedByStudent.getOrDefault(st.getStudentId(), 0);
            double pct = scheduled == 0 ? 0 : round2(attended * 100.0 / scheduled);
            rows.add(new DailyAttendanceResponse.StudentRow(
                    st.getStudentId(), st.getRollNo(), st.getStudentName(),
                    attended > 0 ? "PRESENT" : "ABSENT", attended, pct));
        }

        String courseCode = courseCodeOf(schedule);
        return new DailyAttendanceResponse(
                sessionId, session.getSessionDate(), schedule.getScheduleId(),
                courseCode, courseNameOf(schedule),
                sectionNames(schedule), scheduled, slots, rows);
    }

    // ========== monthly / per-course per-student ==========

    public MonthlyAttendanceResponse monthly(UUID studentId, UUID courseId,
                                             int year, int month) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("Student not found"));

        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        // candidate sessions in the month whose course matches; holidays and
        // cancelled days have no CLASS_SESSION row and therefore never enter
        // the denominator.
        List<ClassSession> sessions = sessionRepository
                .findBySchedule_ScheduleIdInAndSessionDateBetween(
                        scheduleRepository.findAll().stream()
                                .filter(s -> courseIdOf(s) != null
                                        && courseIdOf(s).equals(courseId))
                                .map(ClassSchedule::getScheduleId).toList(),
                        start, end);
        sessions.sort(Comparator.comparing(ClassSession::getSessionDate));

        String courseCode = null;
        String courseName = null;
        if (!sessions.isEmpty()) {
            var cs = sessions.get(0).getSchedule();
            var c = cs.getTeachingAssignment() != null
                    ? cs.getTeachingAssignment().getCourse()
                    : cs.getTeachingGroup() != null ? cs.getTeachingGroup().getCourse() : null;
            if (c != null) { courseCode = c.getCourseCode(); courseName = c.getCourseName(); }
        }

        int scheduled = 0;
        int attended = 0;
        List<MonthlyAttendanceResponse.SessionRow> rows = new ArrayList<>();
        for (ClassSession s : sessions) {
            int sp = spanPeriods(s.getSchedule());
            scheduled += sp;
            int att = attendanceRepository.findBySession_SessionId(s.getSessionId()).stream()
                    .filter(a -> a.getStudent().getStudentId().equals(studentId))
                    .mapToInt(a -> a.getAttendanceStatus() == AttendanceStatus.PRESENT
                            ? AttendanceService.attendedPeriods(
                                    a.getAttendanceStartSlot(), a.getAttendanceEndSlot())
                            : 0)
                    .sum();
            attended += att;
            double pct = sp == 0 ? 0 : round2(att * 100.0 / sp);
            rows.add(new MonthlyAttendanceResponse.SessionRow(
                    s.getSessionId(), s.getSessionDate(), sp, att, pct));
        }
        int absent = Math.max(0, scheduled - attended);
        double percent = scheduled == 0 ? 0 : round2(attended * 100.0 / scheduled);

        return new MonthlyAttendanceResponse(
                studentId, student.getStudentName(), student.getRollNo(),
                courseCode, courseName, year, month,
                scheduled, attended, absent, percent, rows);
    }

    private static UUID courseIdOf(ClassSchedule s) {
        if (s.getTeachingAssignment() != null)
            return s.getTeachingAssignment().getCourse().getCourseId();
        if (s.getTeachingGroup() != null)
            return s.getTeachingGroup().getCourse().getCourseId();
        return null;
    }

    private static String courseCodeOf(ClassSchedule s) {
        if (s.getTeachingAssignment() != null)
            return s.getTeachingAssignment().getCourse().getCourseCode();
        if (s.getTeachingGroup() != null)
            return s.getTeachingGroup().getCourse().getCourseCode();
        return null;
    }

    private static String courseNameOf(ClassSchedule s) {
        if (s.getTeachingAssignment() != null)
            return s.getTeachingAssignment().getCourse().getCourseName();
        if (s.getTeachingGroup() != null)
            return s.getTeachingGroup().getCourse().getCourseName();
        return null;
    }

    private static String sectionNames(ClassSchedule s) {
        List<String> names = new ArrayList<>();
        if (s.getTeachingAssignment() != null) {
            names.add(s.getTeachingAssignment().getSection().getSectionName());
        } else if (s.getTeachingGroup() != null) {
            s.getTeachingGroup().getMembers().forEach(m ->
                    names.add(m.getAssignment().getSection().getSectionName()));
        }
        names.sort(String::compareTo);
        return String.join(" + ", names);
    }

    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
