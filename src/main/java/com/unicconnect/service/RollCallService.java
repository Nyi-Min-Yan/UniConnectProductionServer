package com.unicconnect.service;

import com.unicconnect.dto.response.RollCallScheduleResponse;
import com.unicconnect.dto.response.RollCallStudentsResponse;
import com.unicconnect.entity.Attendance;
import com.unicconnect.entity.AttendanceStatus;
import com.unicconnect.entity.ClassSchedule;
import com.unicconnect.entity.ClassSession;
import com.unicconnect.entity.GenerationSession;
import com.unicconnect.entity.GenerationStatus;
import com.unicconnect.entity.Staff;
import com.unicconnect.entity.StaffPositionAssignment;
import com.unicconnect.entity.Student;
import com.unicconnect.entity.TimeSlot;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.repository.AttendanceRepository;
import com.unicconnect.repository.ClassScheduleRepository;
import com.unicconnect.repository.ClassSessionRepository;
import com.unicconnect.repository.GenerationSessionRepository;
import com.unicconnect.repository.SectionRepository;
import com.unicconnect.repository.StudentRepository;
import com.unicconnect.repository.TimeSlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Roll Call: the lecturer's schedule comes ONLY from the latest PUBLISHED
 * generated timetable; class sessions are created/reused per
 * (schedule, session_date); student lists resolve through each schedule's
 * actual assignment/group section coverage.
 */
@Service
@Transactional(readOnly = true)
public class RollCallService {

    private final GenerationSessionRepository generationRepository;
    private final ClassScheduleRepository scheduleRepository;
    private final ClassSessionRepository sessionRepository;
    private final com.unicconnect.repository.StudentRepository studentRepository;
    private final com.unicconnect.repository.SectionRepository sectionRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final AttendanceRepository attendanceRepository;
    private final com.unicconnect.repository.StaffRepository staffRepository;
    private final com.unicconnect.repository.StaffPositionAssignmentRepository positionAssignmentRepository;
    private final com.unicconnect.util.SecurityUtil securityUtil;

    public RollCallService(GenerationSessionRepository generationRepository,
                           ClassScheduleRepository scheduleRepository,
                           ClassSessionRepository sessionRepository,
                           StudentRepository studentRepository,
                           SectionRepository sectionRepository,
                           TimeSlotRepository timeSlotRepository,
                           AttendanceRepository attendanceRepository,
                           com.unicconnect.repository.StaffRepository staffRepository,
                           com.unicconnect.repository.StaffPositionAssignmentRepository positionAssignmentRepository,
                           com.unicconnect.util.SecurityUtil securityUtil) {
        this.generationRepository = generationRepository;
        this.scheduleRepository = scheduleRepository;
        this.sessionRepository = sessionRepository;
        this.studentRepository = studentRepository;
        this.sectionRepository = sectionRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.attendanceRepository = attendanceRepository;
        this.staffRepository = staffRepository;
        this.positionAssignmentRepository = positionAssignmentRepository;
        this.securityUtil = securityUtil;
    }

    // ========== access ==========

    /** Current staff holding an active LECTURER position today. */
    public Staff requireLecturer() {
        UUID userId = securityUtil.currentUserId();
        Staff staff = staffRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new BusinessRuleException("Only staff can perform roll call"));
        if (!hasActivePosition(staff, "LECTURER")) {
            throw new BusinessRuleException("Only lecturers can perform roll call");
        }
        return staff;
    }

    public boolean hasActivePosition(Staff staff, String positionName) {
        LocalDate today = LocalDate.now();
        return activePositions(staff, today).contains(positionName);
    }

    private Set<String> activePositions(Staff staff, LocalDate today) {
        Set<String> out = new HashSet<>();
        for (StaffPositionAssignment pa
                : positionAssignmentRepository.findByStaff_StaffId(staff.getStaffId())) {
            if (pa.getStartDate() != null && pa.getStartDate().isAfter(today)) continue;
            if (pa.getEndDate() != null && pa.getEndDate().isBefore(today)) continue;
            if (pa.getPosition() != null && pa.getPosition().getPositionName() != null) {
                out.add(pa.getPosition().getPositionName());
            }
        }
        return out;
    }

    // ========== latest published timetable ==========

    public GenerationSession latestPublished() {
        return generationRepository
                .findFirstByStatusAndPublishedAtIsNotNullOrderByPublishedAtDesc(
                        GenerationStatus.PUBLISHED)
                .orElseThrow(() -> new BusinessRuleException(
                        "No published timetable exists yet"));
    }

    // ========== lecturer weekly schedule ==========

    public List<RollCallScheduleResponse> mySchedule(Staff lecturer) {
        GenerationSession published = latestPublished();
        LocalDate today = LocalDate.now();
        List<ClassSchedule> rows = scheduleRepository
                .findByGeneration_GenerationIdWithDetails(published.getGenerationId())
                .stream()
                .filter(s -> ClassScheduleService.coveredStaff(s).contains(lecturer.getStaffId()))
                .sorted(Comparator.comparingInt(ClassSchedule::getDayOfWeek)
                        .thenComparing(s -> s.getStartSlot().getStartTime()))
                .toList();
        List<RollCallScheduleResponse> out = new ArrayList<>();
        for (ClassSchedule s : rows) {
            boolean isToday = s.getDayOfWeek() == today.getDayOfWeek().getValue();
            ClassSession todays = isToday
                    ? sessionRepository.findBySchedule_ScheduleIdAndSessionDate(
                            s.getScheduleId(), today).orElse(null)
                    : null;
            out.add(toScheduleResponse(s, todays));
        }
        return out;
    }

    // ========== current-class auto selection ==========

    /** The class happening RIGHT NOW for this lecturer, or null. */
    public RollCallScheduleResponse currentClass(Staff lecturer) {
        var now = java.time.LocalTime.now();
        var todayName = LocalDate.now().getDayOfWeek().name();
        return mySchedule(lecturer).stream()
                .filter(r -> r.dayName().equalsIgnoreCase(todayName))
                .filter(r -> !now.isBefore(r.startTime()) && now.isBefore(r.endTime()))
                .findFirst()
                .orElse(null);
    }

    // ========== session create/reuse ==========

    @Transactional
    public ClassSession ensureTodaySession(UUID scheduleId, Staff lecturer) {
        return ensureSessionOn(scheduleId, lecturer, LocalDate.now());
    }

    /**
     * Creates (or reuses) the CLASS_SESSION for the given occurrence date.
     * The date MUST correspond to the schedule's timetable weekday — a Monday
     * schedule can never own a Tuesday session. UNIQUE(schedule_id,
     * session_date) guarantees reuse instead of duplication.
     */
    @Transactional
    public ClassSession ensureSessionOn(UUID scheduleId, Staff lecturer, LocalDate sessionDate) {
        ClassSchedule schedule = ownedPublishedSchedule(scheduleId, lecturer);
        LocalDate date = sessionDate != null ? sessionDate : LocalDate.now();
        if (date.getDayOfWeek().getValue() != schedule.getDayOfWeek()) {
            throw new BusinessRuleException(
                    "Selected date " + date + " (" + date.getDayOfWeek()
                            + ") does not match this schedule's teaching day ("
                            + DayOfWeek.of(schedule.getDayOfWeek()) + ").");
        }
        final LocalDate fDate = date;
        return sessionRepository
                .findBySchedule_ScheduleIdAndSessionDate(scheduleId, fDate)
                .orElseGet(() -> {
                    ClassSession session = new ClassSession();
                    session.setSchedule(schedule);
                    session.setSessionDate(fDate);
                    session.setSessionStatus(com.unicconnect.entity.SessionStatus.SCHEDULED);
                    return sessionRepository.save(session);
                });
    }

    /**
     * Create/reuse today's session AND build the API response in the SAME
     * transaction. The controller previously walked lazy associations
     * ({@code schedule.generation.term}, assignment course/section) after the
     * service transaction had closed ??? with {@code open-in-view: false} that
     * threw LazyInitializationException as a generic 500 on every call after
     * the first insert.
     */
    @Transactional
    public com.unicconnect.dto.response.ClassSessionResponse ensureTodaySessionResponse(
            UUID scheduleId, Staff lecturer) {
        return ensureTodaySessionResponse(scheduleId, lecturer, null);
    }

    @Transactional
    public com.unicconnect.dto.response.ClassSessionResponse ensureTodaySessionResponse(
            UUID scheduleId, Staff lecturer, LocalDate sessionDate) {
        ClassSession session = ensureSessionOn(scheduleId, lecturer, sessionDate);
        ClassSchedule schedule = session.getSchedule();
        String courseCode = null;
        UUID sectionId = null;
        String sectionName = null;
        if (schedule.getTeachingAssignment() != null) {
            courseCode = schedule.getTeachingAssignment().getCourse().getCourseCode();
            sectionId = schedule.getTeachingAssignment().getSection().getSectionId();
            sectionName = schedule.getTeachingAssignment().getSection().getSectionName();
        } else if (schedule.getTeachingGroup() != null) {
            courseCode = schedule.getTeachingGroup().getCourse().getCourseCode();
        }
        return new com.unicconnect.dto.response.ClassSessionResponse(
                session.getSessionId(),
                schedule.getScheduleId(),
                schedule.getGeneration().getTerm().getTermId(),
                courseCode,
                sectionId,
                sectionName,
                session.getSessionDate(),
                session.getSessionStatus(),
                session.getStartedAt(),
                session.getEndedAt());
    }

    // ========== students for a schedule (coverage-aware) ==========

    public RollCallStudentsResponse students(UUID scheduleId, UUID sessionId, Staff lecturer) {
        ownedPublishedSchedule(scheduleId, lecturer);

        // AUTHORITATIVE COHORT: course.semester + actual section coverage.
        List<Student> students = cohortRoster(scheduleRepository.findById(scheduleId).orElseThrow());

        ClassSchedule schedule = scheduleRepository.findById(scheduleId).orElseThrow();
        int scheduledPeriods = spanCount(schedule);
        List<TimeSlot> spanSlots = slotsInRange(schedule);

        Map<UUID, Attendance> attendanceByStudent = new LinkedHashMap<>();
        if (sessionId != null) {
            for (Attendance a : attendanceRepository.findBySession_SessionId(sessionId)) {
                attendanceByStudent.putIfAbsent(a.getStudent().getStudentId(), a);
            }
        }

        List<RollCallStudentsResponse.StudentDto> studentDtos = new ArrayList<>();
        for (Student st : students) {
            Attendance a = attendanceByStudent.get(st.getStudentId());
            String status = null;
            String remark = null;
            List<UUID> slotIds = List.of();
            int attended = 0;
            if (a != null) {
                status = a.getAttendanceStatus().name();
                remark = a.getRemark();
                if (a.getAttendanceStatus() == AttendanceStatus.PRESENT
                        && a.getAttendanceStartSlot() != null
                        && a.getAttendanceEndSlot() != null) {
                    // Expand the contiguous stored range into slot ids for the UI.
                    int sOrd = a.getAttendanceStartSlot().getDisplayOrder();
                    int eOrd = a.getAttendanceEndSlot().getDisplayOrder();
                    int lo2 = Math.min(sOrd, eOrd);
                    int hi2 = Math.max(sOrd, eOrd);
                    slotIds = spanSlots.stream()
                            .filter(t -> t.getDisplayOrder() >= lo2 && t.getDisplayOrder() <= hi2)
                            .map(TimeSlot::getSlotId)
                            .toList();
                    attended = AttendanceService.attendedPeriods(
                            a.getAttendanceStartSlot(), a.getAttendanceEndSlot());
                }
            }
            studentDtos.add(new RollCallStudentsResponse.StudentDto(
                    st.getStudentId(), st.getRollNo(), st.getStudentName(),
                    a != null ? a.getAttendanceId() : null,
                    status, remark, slotIds, attended));
        }

        List<RollCallStudentsResponse.SlotDto> slotDtos = spanSlots.stream()
                .map(t -> new RollCallStudentsResponse.SlotDto(
                        t.getSlotId(), t.getPeriodNo(),
                        t.getStartTime().toString(), t.getEndTime().toString()))
                .toList();

        List<String> secNames = new ArrayList<>(coveredSectionNames(scheduleId));
        secNames.sort(String::compareTo);
        var course = courseOfRow(schedule);
        return new RollCallStudentsResponse(
                scheduleId,
                course != null ? course.getCourseId() : null,
                course != null ? course.getCourseCode() : null,
                course != null ? course.getCourseName() : null,
                course != null && course.getSemester() != null
                        ? course.getSemester().getSemesterId() : null,
                semesterNumberOfRow(schedule),
                secNames, scheduledPeriods, slotDtos,
                studentDtos.size(), studentDtos);
    }

    // ========== session delete ==========

    /**
     * Deletes a CLASS_SESSION and ALL of its attendance rows, transactionally.
     * Authorization reuses the existing rules: active LECTURER position,
     * lecturer coverage of the schedule, and the schedule must belong to the
     * latest PUBLISHED timetable. The attendance FK is ON DELETE CASCADE, so
     * removing the session also removes its attendance at the database level;
     * the explicit delete keeps JPA state consistent within the transaction.
     */
    @Transactional
    public void deleteSession(UUID sessionId, Staff lecturer) {
        ClassSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new com.unicconnect.exception.ResourceNotFoundException(
                        "Class session not found"));
        authorizeLecturerForSchedule(lecturer, session.getSchedule());
        attendanceRepository.deleteBySession_SessionId(sessionId);
        sessionRepository.delete(session);
    }

    /**
     * Cohort History: ALL submitted roll calls across ALL published schedules
     * belonging to the lecturer that match the selected course + semester +
     * optional section. One submitted CLASS_SESSION = one column. Empty
     * sessions are excluded. Dates come from class_sessions.session_date.
     */
    @Transactional(readOnly = true)
    public com.unicconnect.dto.response.RollCallHistoryResponse historyByCohort(
            String courseCode, Integer semesterNo, String sectionName,
            LocalDate from, LocalDate to) {
        Staff lecturer = requireLecturer();
        GenerationSession published = latestPublished();
        if (from == null) from = LocalDate.now().withDayOfMonth(1);
        if (to == null) to = from.withDayOfMonth(from.lengthOfMonth());

        // Find ALL matching published schedules for this lecturer
        List<ClassSchedule> matching = scheduleRepository
                .findByGeneration_GenerationIdWithDetails(published.getGenerationId())
                .stream()
                .filter(s -> ClassScheduleService.coveredStaff(s).contains(lecturer.getStaffId()))
                .filter(s -> {
                    var c = courseOfRow(s);
                    return c != null && courseCode.equals(c.getCourseCode())
                        && c.getSemester() != null
                        && semesterNo.equals(c.getSemester().getSemesterNo())
                        && (sectionName == null || sectionName.isBlank()
                            || ClassScheduleService.coveredSections(s).stream().anyMatch(secId -> {
                                var sec = sectionRepository.findById(secId).orElse(null);
                                return sec != null && sectionName.equals(sec.getSectionName());
                            }));
                })
                .sorted(Comparator.comparingInt(ClassSchedule::getDayOfWeek)
                        .thenComparing(s -> s.getStartSlot().getStartTime()))
                .toList();

        if (matching.isEmpty()) {
            return new com.unicconnect.dto.response.RollCallHistoryResponse(
                    new com.unicconnect.dto.response.RollCallHistoryResponse.CohortInfo(
                            null, courseCode, null, semesterNo,
                            sectionName != null ? List.of(sectionName) : List.of(),
                            false,
                            List.of()),
                    List.of(), List.of());
        }

        // Collect union of covered sections and slot times from first schedule
        ClassSchedule primary = matching.get(0);
        Set<String> allSecNames = new java.util.TreeSet<>();
        matching.forEach(s -> allSecNames.addAll(coveredSectionNames(s.getScheduleId())));
        boolean shared = matching.size() > 1 || primary.getTeachingGroup() != null;

        var info = new com.unicconnect.dto.response.RollCallHistoryResponse.CohortInfo(
                courseOfRow(primary).getCourseId(),
                courseCode, courseNameOfRow(primary),
                semesterNumberOfRow(primary),
                new ArrayList<>(allSecNames),
                shared,
                slotsInRange(primary).stream()
                        .map(t -> new com.unicconnect.dto.response.RollCallHistoryResponse.SlotTime(
                                t.getSlotId(), t.getStartTime().toString(), t.getEndTime().toString()))
                        .toList());

        // Load sessions across ALL matching schedules in date range
        List<UUID> schedIds = matching.stream().map(ClassSchedule::getScheduleId).toList();
        List<ClassSession> sessions = sessionRepository
                .findBySchedule_ScheduleIdInAndSessionDateBetweenOrderBySessionDateAsc(
                        schedIds, from, to);

        // Bulk attendance load
        List<UUID> sessIds = sessions.stream().map(ClassSession::getSessionId).toList();
        Map<UUID, Map<UUID, Attendance>> byStudentSession = new HashMap<>();
        if (!sessIds.isEmpty()) {
            for (Attendance a : attendanceRepository.findBySession_SessionIdIn(sessIds)) {
                byStudentSession
                        .computeIfAbsent(a.getStudent().getStudentId(), k -> new HashMap<>())
                        .put(a.getSession().getSessionId(), a);
            }
        }

        // Only sessions with at least one attendance record.
        // Collect the set of sessionIds that have attendance, then filter.
        Set<UUID> submittedSessIds = new HashSet<>();
        byStudentSession.forEach((stuId, sessMap) -> submittedSessIds.addAll(sessMap.keySet()));
        List<ClassSession> submitted = sessions.stream()
                .filter(cs -> submittedSessIds.contains(cs.getSessionId()))
                .toList();

        // Session columns
        List<com.unicconnect.dto.response.RollCallHistoryResponse.SessionColumn> cols =
                submitted.stream()
                        .map(s -> new com.unicconnect.dto.response.RollCallHistoryResponse.SessionColumn(
                                s.getSessionId(),
                                s.getSessionDate(),
                                s.getSessionDate().getDayOfWeek().name(),
                                s.getSchedule().getStartSlot().getStartTime().toString(),
                                s.getSchedule().getEndSlot().getEndTime().toString(),
                                spanCount(s.getSchedule()),
                                s.getSchedule().getScheduleId()))
                        .toList();

        // Combined roster: deduplicate by student_id
        Map<UUID, Student> rosterMap = new LinkedHashMap<>();
        Set<UUID> coveredSections = new HashSet<>();
        matching.forEach(s -> coveredSections.addAll(ClassScheduleService.coveredSections(s)));
        for (UUID secId : coveredSections) {
            for (Student st : studentRepository.findBySection_SectionIdAndSemester_SemesterId(
                    secId, courseOfRow(primary).getSemester().getSemesterId())) {
                rosterMap.putIfAbsent(st.getStudentId(), st);
            }
        }

        // Build student rows aligned with submitted session columns
        final List<ClassSession> fSubmitted = submitted;
        final Map<UUID, Map<UUID, Attendance>> fAttMap = byStudentSession;
        List<com.unicconnect.dto.response.RollCallHistoryResponse.StudentRow> rows =
                rosterMap.values().stream()
                        .sorted(Comparator.comparing(Student::getRollNo,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(st -> {
                            Map<UUID, Attendance> mine =
                                    fAttMap.getOrDefault(st.getStudentId(), Map.of());
                            int totSched = 0, totAtt = 0;
                            List<com.unicconnect.dto.response.RollCallHistoryResponse.Cell> cells =
                                    new ArrayList<>();
                            for (ClassSession cs : fSubmitted) {
                                Attendance a = mine.get(cs.getSessionId());
                                String status = null;
                                UUID startSid = null, endSid = null;
                                String remark = null;
                                String marker = null;
                                int att = 0;
                                int sched = spanCount(cs.getSchedule());
                                if (a != null) {
                                    status = a.getAttendanceStatus().name();
                                    remark = a.getRemark();
                                    marker = a.getMarkedByStaff() != null
                                            ? a.getMarkedByStaff().getStaffName() : null;
                                    if (a.getAttendanceStatus() == AttendanceStatus.PRESENT
                                            && a.getAttendanceStartSlot() != null
                                            && a.getAttendanceEndSlot() != null) {
                                        startSid = a.getAttendanceStartSlot().getSlotId();
                                        endSid = a.getAttendanceEndSlot().getSlotId();
                                        att = AttendanceService.attendedPeriods(
                                                a.getAttendanceStartSlot(),
                                                a.getAttendanceEndSlot());
                                    }
                                }
                                totSched += sched;
                                totAtt += att;
                                cells.add(new com.unicconnect.dto.response.RollCallHistoryResponse.Cell(
                                        a != null ? a.getAttendanceId() : null,
                                        cs.getSessionId(), status, att, sched,
                                        startSid, endSid, remark, marker));
                            }
                            double pct = totSched == 0 ? 0.0
                                    : Math.round((totAtt * 100.0 / totSched) * 100.0) / 100.0;
                            return new com.unicconnect.dto.response.RollCallHistoryResponse.StudentRow(
                                    st.getStudentId(), st.getRollNo(), st.getStudentName(),
                                    cells, totSched, totAtt, pct);
                        })
                        .toList();

        return new com.unicconnect.dto.response.RollCallHistoryResponse(info, cols, rows);
    }

    private static UUID endId(UUID v) { return v; }

    /**
     * Valid timetable occurrence dates for ONE lecturer-owned schedule of the
     * latest PUBLISHED timetable, between from..to (inclusive). Pure function
     * of schedule.day_of_week — no CLASS_SESSION is required to exist.
     */
    @Transactional(readOnly = true)
    public List<LocalDate> occurrences(UUID scheduleId, LocalDate from, LocalDate to, Staff lecturer) {
        ClassSchedule schedule = ownedPublishedSchedule(scheduleId, lecturer);
        int dow = schedule.getDayOfWeek();
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() == dow) dates.add(d);
        }
        return dates;
    }


    /**
     * Previously submitted Roll Calls for ONE lecturer-owned schedule of the
     * latest PUBLISHED timetable, between from..to (inclusive; defaults to the
     * current month). Columns = actual CLASS_SESSIONS only (a holiday with no
     * session produces NO column); cells are derived dynamically from
     * ATTENDANCE + its actual slot range. Bulk queries only: 1 schedule +
     * 1 session list + 1 student pass + 1 attendance fetch.
     */
    @Transactional(readOnly = true)
    // ========== authorization helper ==========

    public void authorizeLecturerForSchedule(Staff staff, ClassSchedule schedule) {
        if (!hasActivePosition(staff, "LECTURER")) {
            throw new BusinessRuleException("Only lecturers can submit roll call");
        }
        if (!ClassScheduleService.coveredStaff(schedule).contains(staff.getStaffId())) {
            throw new BusinessRuleException(
                    "You are not the assigned lecturer for this schedule");
        }
        GenerationSession published = latestPublished();
        if (!schedule.getGeneration().getGenerationId()
                .equals(published.getGenerationId())) {
            throw new BusinessRuleException(
                    "Schedule does not belong to the latest published timetable");
        }
    }

    private ClassSchedule ownedPublishedSchedule(UUID scheduleId, Staff lecturer) {
        ClassSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessRuleException("Schedule not found"));
        authorizeLecturerForSchedule(lecturer, schedule);
        return schedule;
    }

    // ========== shared internals ==========

    /**
     * AUTHORITATIVE COHORT RULE — a section name is shared by several semester
     * cohorts, so the Roll Call roster is ALWAYS:
     *   student.semester_id == course.semester_id  (resolved from the
     *       schedule's teaching assignment / group course relationship)
     *   AND student.section_id IN schedule coverage.
     * One query for any number of covered sections. Deduplicated by id.
     */
    List<Student> cohortRoster(ClassSchedule schedule) {
        var course = courseOfRow(schedule);
        if (course == null || course.getSemester() == null) {
            throw new BusinessRuleException(
                    "Schedule course/semester could not be resolved for roll call");
        }
        Set<UUID> sections = ClassScheduleService.coveredSections(schedule);
        if (sections.isEmpty()) {
            throw new BusinessRuleException("Schedule has no section coverage");
        }
        return studentRepository
                .findBySection_SectionIdInAndSemester_SemesterId(
                        new ArrayList<>(sections), course.getSemester().getSemesterId())
                .stream()
                .sorted(Comparator.comparing(Student::getRollNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    static com.unicconnect.entity.Course courseOfRow(ClassSchedule s) {
        if (s.getTeachingAssignment() != null)
            return s.getTeachingAssignment().getCourse();
        if (s.getTeachingGroup() != null)
            return s.getTeachingGroup().getCourse();
        return null;
    }

    Set<UUID> coveredSectionIds(UUID scheduleId) {
        ClassSchedule s = scheduleRepository.findById(scheduleId).orElseThrow();
        return ClassScheduleService.coveredSections(s);
    }

    Set<String> coveredSectionNames(UUID scheduleId) {
        ClassSchedule s = scheduleRepository.findById(scheduleId).orElseThrow();
        Set<String> names = new LinkedHashSet<>();
        if (s.getTeachingAssignment() != null) {
            names.add(s.getTeachingAssignment().getSection().getSectionName());
        } else if (s.getTeachingGroup() != null) {
            s.getTeachingGroup().getMembers().forEach(m ->
                    names.add(m.getAssignment().getSection().getSectionName()));
        }
        return names;
    }

    static String dayName(int iso) {
        return DayOfWeek.of(iso).getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
    }

    private RollCallScheduleResponse toScheduleResponse(ClassSchedule s, ClassSession todays) {
        List<String> names = new ArrayList<>(coveredSectionNames(s.getScheduleId()));
        names.sort(String::compareTo);
        return new RollCallScheduleResponse(
                s.getScheduleId(),
                s.getDayOfWeek(),
                dayName(s.getDayOfWeek()),
                s.getStartSlot().getStartTime(),
                s.getEndSlot().getEndTime(),
                spanCount(s),
                courseCodeOfRow(s),
                courseNameOfRow(s),
                semesterNumberOfRow(s),
                names,
                s.getTeachingGroup() != null,
                todays != null ? todays.getSessionId() : null,
                todays != null && todays.getSessionStatus()
                        == com.unicconnect.entity.SessionStatus.COMPLETED);
    }

    private int spanCount(ClassSchedule s) {
        return s.getEndSlot().getDisplayOrder() - s.getStartSlot().getDisplayOrder() + 1;
    }

    private List<TimeSlot> slotsInRange(ClassSchedule s) {
        int lo = Math.min(s.getStartSlot().getDisplayOrder(), s.getEndSlot().getDisplayOrder());
        int hi = Math.max(s.getStartSlot().getDisplayOrder(), s.getEndSlot().getDisplayOrder());
        return timeSlotRepository.findAll().stream()
                .filter(t -> t.getDisplayOrder() >= lo && t.getDisplayOrder() <= hi)
                .sorted(Comparator.comparing(TimeSlot::getDisplayOrder))
                .toList();
    }

    static String courseCodeOfRow(ClassSchedule s) {
        if (s.getTeachingAssignment() != null)
            return s.getTeachingAssignment().getCourse().getCourseCode();
        if (s.getTeachingGroup() != null)
            return s.getTeachingGroup().getCourse().getCourseCode();
        return null;
    }

    static String courseNameOfRow(ClassSchedule s) {
        if (s.getTeachingAssignment() != null)
            return s.getTeachingAssignment().getCourse().getCourseName();
        if (s.getTeachingGroup() != null)
            return s.getTeachingGroup().getCourse().getCourseName();
        return null;
    }

    static Integer semesterNumberOfRow(ClassSchedule s) {
        var sem = semesterOfRowInternal(s);
        return sem != null ? sem.getSemesterNo() : null;
    }

    private static com.unicconnect.entity.Semester semesterOfRowInternal(ClassSchedule s) {
        if (s.getTeachingAssignment() != null)
            return s.getTeachingAssignment().getCourse().getSemester();
        if (s.getTeachingGroup() != null)
            return s.getTeachingGroup().getCourse().getSemester();
        return null;
    }

    static List<String> sectionNamesSortedForRow(ClassSchedule s) {
        Set<String> names = new LinkedHashSet<>();
        if (s.getTeachingAssignment() != null) {
            names.add(s.getTeachingAssignment().getSection().getSectionName());
        } else if (s.getTeachingGroup() != null) {
            s.getTeachingGroup().getMembers().forEach(m ->
                    names.add(m.getAssignment().getSection().getSectionName()));
        }
        return names.stream().sorted().toList();
    }
}

