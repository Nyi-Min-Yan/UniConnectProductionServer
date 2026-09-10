package com.unicconnect.service;

import com.unicconnect.dto.request.MarkAttendanceRequest;
import com.unicconnect.dto.response.RollCallStudentsResponse;
import com.unicconnect.entity.Attendance;
import com.unicconnect.entity.ClassSchedule;
import com.unicconnect.entity.ClassSession;
import com.unicconnect.entity.GenerationSession;
import com.unicconnect.entity.GenerationStatus;
import com.unicconnect.entity.Staff;
import com.unicconnect.entity.Student;
import com.unicconnect.entity.TimeSlot;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.exception.ValidationException;
import com.unicconnect.repository.AttendanceRepository;
import com.unicconnect.repository.ClassScheduleRepository;
import com.unicconnect.repository.ClassSessionRepository;
import com.unicconnect.repository.GenerationSessionRepository;
import com.unicconnect.repository.StaffRepository;
import com.unicconnect.repository.StudentRepository;
import com.unicconnect.repository.TimeSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Roll Call / Attendance module verification against live data inside a
 * rolled-back transaction: sessions, attendance decisions, the ACTUAL period
 * range columns, authorization, published-timetable scoping and dynamic
 * daily/monthly calculations.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class RollCallAttendanceIntegrationTest {

    @Autowired RollCallService rollCallService;
    @Autowired AttendanceService attendanceService;
    @Autowired AttendanceCalculationService calculationService;
    @Autowired GenerationSessionRepository generationRepository;
    @Autowired ClassScheduleRepository scheduleRepository;
    @Autowired ClassSessionRepository sessionRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired StaffRepository staffRepository;
    @Autowired StudentRepository studentRepository;
    @Autowired TimeSlotRepository timeSlotRepository;

    private GenerationSession published;
    private ClassSchedule schedule;      // a singleton delivery from the published generation
    private Staff lecturer;              // the covering lecturer of `schedule`
    private int spanPeriods;

    @BeforeEach
    void setUpAsCoveringLecturer() {
        published = generationRepository
                .findFirstByStatusAndPublishedAtIsNotNullOrderByPublishedAtDesc(
                        GenerationStatus.PUBLISHED)
                .orElseThrow(() -> new IllegalStateException(
                        "test requires a PUBLISHED generation"));
        schedule = scheduleRepository
                .findByGeneration_GenerationIdWithDetails(published.getGenerationId())
                .stream()
                .filter(s -> s.getTeachingAssignment() != null)
                // Prefer a Semester-2 Section-A delivery so the cohort roster
                // is deterministic in the seeded dataset (Sem2/A = 20).
                .filter(s -> {
                    var c = RollCallService.courseOfRow(s);
                    return c != null && c.getSemester() != null
                            && c.getSemester().getSemesterNo() == 2
                            && "A".equals(s.getTeachingAssignment().getSection().getSectionName());
                })
                .findFirst()
                .orElseGet(() -> scheduleRepository
                        .findByGeneration_GenerationIdWithDetails(published.getGenerationId())
                        .stream()
                        .filter(s -> s.getTeachingAssignment() != null)
                        .findFirst().orElseThrow());
        var covering = ClassScheduleService.coveredStaff(schedule);
        lecturer = staffRepository.findAll().stream()
                .filter(st -> covering.contains(st.getStaffId()))
                .filter(st -> st.getUser() != null)
                .findFirst().orElseThrow();
        spanPeriods = AttendanceCalculationService.spanPeriods(schedule);
        authAs(lecturer);
    }

    private void authAs(Staff staff) {
        UUID userId = staff.getUser().getUserId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }

    private TimeSlot slotAt(int indexWithinSpan) {
        int lo = Math.min(schedule.getStartSlot().getDisplayOrder(),
                schedule.getEndSlot().getDisplayOrder());
        return timeSlotByDisplayOrder(lo + indexWithinSpan);
    }

    private TimeSlot timeSlotByDisplayOrder(int order) {
        return timeSlotRepository.findAll().stream()
                .filter(t -> t.getDisplayOrder() == order)
                .findFirst().orElseThrow();
    }

    private record Roster(UUID sessionId, List<UUID> spanSlots, List<UUID> studentIds) {}

    private Roster freshRoster() {
        ClassSession s = rollCallService.ensureTodaySession(
                schedule.getScheduleId(), lecturer);
        RollCallStudentsResponse roster = rollCallService.students(
                schedule.getScheduleId(), s.getSessionId(), lecturer);
        return new Roster(s.getSessionId(),
                roster.slots().stream().map(RollCallStudentsResponse.SlotDto::slotId).toList(),
                roster.students().stream().map(RollCallStudentsResponse.StudentDto::studentId).toList());
    }

    private MarkAttendanceRequest.AttendanceEntry entry(UUID stu, boolean present,
                                                        Integer startIdx, Integer endIdx) {
        return new MarkAttendanceRequest.AttendanceEntry(
                stu,
                present ? com.unicconnect.entity.AttendanceStatus.PRESENT
                        : com.unicconnect.entity.AttendanceStatus.ABSENT,
                null,
                startIdx == null ? null : slotAt(startIdx).getSlotId(),
                endIdx == null ? null : slotAt(endIdx).getSlotId());
    }

    // ===== A/D: authorization =====

    @Test
    void lecturerCanCreateAndFillSession() {
        Roster r = freshRoster();
        assertNotNull(r.sessionId());
        assertEquals(spanPeriods, r.spanSlots().size());
    }

    @Test
    void nonLecturerStaffIsRejected() {
        // find a staff member WITHOUT an active LECTURER assignment
        Staff outsider = staffRepository.findAll().stream()
                .filter(st -> !rollCallService.hasActivePosition(st, "LECTURER"))
                .filter(st -> st.getUser() != null)
                .findFirst().orElse(null);
        if (outsider == null) return; // dataset has none: skip gracefully

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> rollCallService.ensureTodaySession(
                        schedule.getScheduleId(), outsider));
        assertTrue(ex.getMessage().contains("lecturer"));
    }

    // ===== B: latest published timetable only =====

    @Test
    void scheduleOutsideLatestPublishedIsRejected() {
        ClassSchedule stale = scheduleRepository.findAll().stream()
                .filter(s -> !s.getGeneration().getGenerationId()
                        .equals(published.getGenerationId()))
                .findFirst().orElse(null);
        if (stale == null) return;

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> rollCallService.authorizeLecturerForSchedule(lecturer, stale));
        assertTrue(ex.getMessage().contains("latest published"),
                ex.getMessage());
    }

    // ===== E/F: partial ranges persist exactly; duplicate-safe resubmit =====

    @Test
    void partialAttendance_creditsExactRange_andResubmitReplaces() {
        Roster r = freshRoster();
        UUID stuA = r.studentIds().get(0);
        UUID stuB = r.studentIds().get(1);

        // A full range (0..span-1), B only the second period (1..1).
        attendanceService.markAttendance(r.sessionId(),
                new MarkAttendanceRequest(List.of(
                        entry(stuA, true, 0, spanPeriods - 1),
                        entry(stuB, true, Math.min(1, spanPeriods - 1),
                                Math.min(1, spanPeriods - 1)))));

        Attendance attA = attendanceRepository.findBySession_SessionId(r.sessionId())
                .stream().filter(x -> x.getStudent().getStudentId().equals(stuA))
                .findFirst().orElseThrow();
        assertEquals(schedule.getStartSlot().getDisplayOrder(),
                attA.getAttendanceStartSlot().getDisplayOrder());
        assertEquals(schedule.getEndSlot().getDisplayOrder(),
                attA.getAttendanceEndSlot().getDisplayOrder());

        // duplicate decision prevented: second mark REPLACES, never duplicates
        attendanceService.markAttendance(r.sessionId(),
                new MarkAttendanceRequest(List.of(
                        entry(stuA, true, Math.min(1, spanPeriods - 1),
                                Math.min(1, spanPeriods - 1)))));
        long rows = attendanceRepository.findBySession_SessionId(r.sessionId()).stream()
                .filter(x -> x.getStudent().getStudentId().equals(stuA)).count();
        assertEquals(1, rows, "no duplicate ATTENDANCE rows");
        Attendance attA2 = attendanceRepository.findBySession_SessionId(r.sessionId())
                .stream().filter(x -> x.getStudent().getStudentId().equals(stuA))
                .findFirst().orElseThrow();
        assertEquals(slotAt(Math.min(1, spanPeriods - 1)).getSlotId(),
                attA2.getAttendanceStartSlot().getSlotId(),
                "range replaced with the latest submission");
    }

    @Test
    void singlePeriodRanges_calculateCorrectly() {
        Roster r = freshRoster();
        UUID s0 = r.studentIds().get(0);
        if (r.studentIds().size() < 3) return; // need up to 3 students
        UUID s1 = r.studentIds().get(1);
        UUID s2 = r.studentIds().get(2);

        attendanceService.markAttendance(r.sessionId(), new MarkAttendanceRequest(List.of(
                entry(s0, true, 0, 0),                      // first period -> 1
                entry(s1, true, spanPeriods - 1, spanPeriods - 1), // last period -> 1
                entry(s2, true, Math.min(1, spanPeriods - 1), spanPeriods - 1)))); // tail

        var daily = calculationService.daily(r.sessionId());
        assertEquals(spanPeriods, daily.scheduledPeriods());
        assertEquals(1, rowAttended(daily, s0));
        assertEquals(1, rowAttended(daily, s1));
        assertEquals(spanPeriods - Math.min(1, spanPeriods - 1), rowAttended(daily, s2));
    }

    // ===== ABSENT semantics =====

    @Test
    void absentStoresNullRange_andCountsZero() {
        Roster r = freshRoster();
        UUID stu = r.studentIds().get(0);

        attendanceService.markAttendance(r.sessionId(),
                new MarkAttendanceRequest(List.of(entry(stu, false, null, null))));

        Attendance a = attendanceRepository.findBySession_SessionId(r.sessionId())
                .stream().filter(x -> x.getStudent().getStudentId().equals(stu))
                .findFirst().orElseThrow();
        assertNull(a.getAttendanceStartSlot());
        assertNull(a.getAttendanceEndSlot());
        var daily = calculationService.daily(r.sessionId());
        assertEquals(0, rowAttended(daily, stu));
        assertEquals(0.0, rowPercent(daily, stu));
    }

    @Test
    void absentWithNonNullRangeRejected_presentWithNullRangeRejected() {
        Roster r = freshRoster();
        UUID s0 = r.studentIds().get(0);
        UUID s1 = r.studentIds().size() > 1 ? r.studentIds().get(1) : s0;

        // ABSENT with any range is rejected
        assertThrows(ValidationException.class, () -> attendanceService.markAttendance(
                r.sessionId(), new MarkAttendanceRequest(List.of(entry(s0, false, 0, 0)))));
        // PRESENT with NULL start / NULL end rejected
        assertThrows(ValidationException.class, () -> attendanceService.markAttendance(
                r.sessionId(), new MarkAttendanceRequest(List.of(
                        new MarkAttendanceRequest.AttendanceEntry(s0,
                                com.unicconnect.entity.AttendanceStatus.PRESENT,
                                null, null, slotAt(0).getSlotId())))));
        assertThrows(ValidationException.class, () -> attendanceService.markAttendance(
                r.sessionId(), new MarkAttendanceRequest(List.of(
                        new MarkAttendanceRequest.AttendanceEntry(s1,
                                com.unicconnect.entity.AttendanceStatus.PRESENT,
                                null, slotAt(0).getSlotId(), null)))));
    }

    // ===== G: invalid ranges =====

    @Test
    void slotOutsideScheduleRangeIsRejected() {
        Roster r = freshRoster();
        UUID stu = r.studentIds().get(0);

        // a real slot that lies outside this schedule's span
        int lo = Math.min(schedule.getStartSlot().getDisplayOrder(),
                schedule.getEndSlot().getDisplayOrder());
        int hi = Math.max(schedule.getStartSlot().getDisplayOrder(),
                schedule.getEndSlot().getDisplayOrder());
        TimeSlot outside = timeSlotRepository.findAll().stream()
                .filter(t -> t.getDisplayOrder() < lo || t.getDisplayOrder() > hi)
                .findFirst().orElse(null);
        if (outside == null) return; // schedule spans the whole day

        ValidationException ex = assertThrows(ValidationException.class,
                () -> attendanceService.markAttendance(r.sessionId(),
                        new MarkAttendanceRequest(List.of(
                                new MarkAttendanceRequest.AttendanceEntry(stu,
                                        com.unicconnect.entity.AttendanceStatus.PRESENT,
                                        null, outside.getSlotId(), outside.getSlotId())))));
        assertTrue(ex.getMessage().contains("outside the scheduled range"));

        // unknown id rejected as well
        assertThrows(ValidationException.class,
                () -> attendanceService.markAttendance(r.sessionId(),
                        new MarkAttendanceRequest(List.of(
                                new MarkAttendanceRequest.AttendanceEntry(stu,
                                        com.unicconnect.entity.AttendanceStatus.PRESENT,
                                        null, UUID.randomUUID(), UUID.randomUUID())))));
    }

    @Test
    void startAfterEndIsRejected() {
        Roster r = freshRoster();
        UUID stu = r.studentIds().get(0);
        if (spanPeriods < 2) return; // need >=2 periods to invert

        ValidationException ex = assertThrows(ValidationException.class,
                () -> attendanceService.markAttendance(r.sessionId(),
                        new MarkAttendanceRequest(List.of(entry(stu, true, 1, 0)))));
        assertTrue(ex.getMessage().contains("cannot be after"));
    }

    // ===== H: daily + monthly dynamic calculations =====

    @Test
    void monthlyAggregatesActualSessions_only() {
        Roster r = freshRoster();
        UUID stu = r.studentIds().get(0);

        LocalDate today = LocalDate.now();
        attendanceService.markAttendance(r.sessionId(), new MarkAttendanceRequest(List.of(
                entry(stu, true, 0, Math.max(0, spanPeriods - 2))))); // all but one period

        // add one more session for YESTERDAY directly at repository level to
        // simulate a second held class in the same month.
        ClassSession extra = new ClassSession();
        extra.setSchedule(schedule);
        extra.setSessionDate(today.minusDays(1));
        final ClassSession saved = sessionRepository.save(extra);
        Attendance e = new Attendance();
        e.setSession(saved);
        Student studentRef = studentRepository.findById(stu).orElseThrow();
        e.setStudent(studentRef);
        e.setAttendanceStatus(com.unicconnect.entity.AttendanceStatus.ABSENT);
        attendanceRepository.save(e); // absent contributes zero

        var month = calculationService.monthly(stu, courseIdOfRow(),
                today.getYear(), today.getMonthValue());

        int expectedScheduled = 2 * spanPeriods;      // two actual sessions
        int expectedAttended = spanPeriods - 1;       // yesterday absent adds 0
        assertEquals(expectedScheduled, month.scheduledPeriods());
        assertEquals(expectedAttended, month.attendedPeriods());
        assertEquals(expectedScheduled - expectedAttended, month.absentPeriods());
        double pct = Math.round((expectedAttended * 100.0 / expectedScheduled) * 100.0) / 100.0;
        assertEquals(pct, month.attendancePercent());

        // a month with no sessions must yield zeros (no fabricated denominator)
        var emptyMonth = calculationService.monthly(stu, courseIdOfRow(),
                today.plusMonths(3).getYear(), today.plusMonths(3).getMonthValue());
        assertEquals(0, emptyMonth.scheduledPeriods());
        assertEquals(0, emptyMonth.attendedPeriods());
        assertEquals(0.0, emptyMonth.attendancePercent());
    }

    // ===== History =====

    @Test
    void historyBuildsColumnsFromActualSessions_andRejectsForeignSchedules() {
        Roster r = freshRoster();
        UUID stuA = r.studentIds().get(0);
        UUID stuB = r.studentIds().size() > 1 ? r.studentIds().get(1) : stuA;
        UUID stuC = r.studentIds().size() > 2 ? r.studentIds().get(2) : stuA;

        attendanceService.markAttendance(r.sessionId(), new MarkAttendanceRequest(List.of(
                entry(stuA, true, 0, spanPeriods - 1),             // full
                entry(stuB, true, 0, 0),                           // partial (>=1 period classes)
                entry(stuC, false, null, null))));                 // absent

        // a second actual session YESTERDAY with no attendance rows at all
        ClassSession extra = new ClassSession();
        extra.setSchedule(schedule);
        extra.setSessionDate(LocalDate.now().minusDays(1));
        sessionRepository.save(extra);

        var hist = rollCallService.history(schedule.getScheduleId(),
                LocalDate.now().withDayOfMonth(1), LocalDate.now());

        assertEquals(2, hist.sessions().size(), "two actual sessions -> two columns");
        assertEquals(spanPeriods * 2,
                hist.students().stream().mapToInt(s -> s.totalScheduledPeriods()).distinct().max().orElse(0));
        assertFalse(hist.students().isEmpty());
        assertEquals(hist.sessions().size(),
                hist.students().get(0).attendance().size(), "cell per session per student");

        var rowA = hist.students().stream()
                .filter(s -> s.studentId().equals(stuA)).findFirst().orElseThrow();
        assertEquals(spanPeriods, rowA.totalAttendedPeriods());
        double pct = Math.round((spanPeriods * 100.0 / (spanPeriods * 2)) * 100.0) / 100.0;
        assertEquals(pct, rowA.attendancePercentage());

        // missing attendance rows are "Not Recorded" (null), never ABSENT
        if (hist.students().size() > 3) {
            var unmarked = hist.students().get(3);
            assertTrue(unmarked.attendance().stream().allMatch(c -> c.status() == null),
                    "no ATTENDANCE row must surface as Not Recorded, not ABSENT");
            assertEquals(0, unmarked.totalScheduledPeriods() == 0 ? 0
                    : unmarked.totalAttendedPeriods());
        }

        // month filter excludes everything from LAST month
        var lastMonth = rollCallService.history(schedule.getScheduleId(),
                LocalDate.now().minusMonths(1).withDayOfMonth(1),
                LocalDate.now().minusMonths(1).withDayOfMonth(1).plusMonths(1).minusDays(1));
        assertTrue(lastMonth.sessions().isEmpty(), "no fabricated holiday columns");

        // foreign lecturer's schedule rejected
        Staff outsider = staffRepository.findAll().stream()
                .filter(st -> !ClassScheduleService.coveredStaff(schedule).contains(st.getStaffId()))
                .filter(st -> rollCallService.hasActivePosition(st, "LECTURER"))
                .filter(st -> st.getUser() != null)
                .findFirst().orElse(null);
        if (outsider != null) {
            authAs(outsider);
            assertThrows(BusinessRuleException.class,
                    () -> rollCallService.history(schedule.getScheduleId(),
                            LocalDate.now().withDayOfMonth(1), LocalDate.now()));
            authAs(lecturer);
        }
    }

    // ===== COHORT RULE: course.semester + section, never section alone =====

    @Test
    void rosterContainsOnlyCourseSemesterCohort() {
        Roster r = freshRoster();
        var course = RollCallService.courseOfRow(schedule);
        UUID courseSemId = course.getSemester().getSemesterId();
        var covered = ClassScheduleService.coveredSections(schedule);

        var roster = rollCallService.students(schedule.getScheduleId(),
                r.sessionId(), lecturer);
        assertFalse(roster.students().isEmpty());
        assertEquals(roster.students().size(), roster.studentCount());
        for (var dto : roster.students()) {
            Student s = studentRepository.findById(dto.studentId()).orElseThrow();
            assertEquals(courseSemId, s.getSemester().getSemesterId(),
                    "roster must only contain course-semester students");
            assertTrue(covered.contains(s.getSection().getSectionId()),
                    "roster must only contain covered-section students");
        }
    }

    @Test
    void wrongSemesterOrUncoveredSectionStudentIsRejectedOnSubmission() {
        Roster r = freshRoster();
        var course = RollCallService.courseOfRow(schedule);
        UUID courseSemId = course.getSemester().getSemesterId();

        // same section, WRONG semester -> must be rejected
        Student wrongSem = studentRepository.findAll().stream()
                .filter(s -> s.getSection() != null
                        && ClassScheduleService.coveredSections(schedule)
                                .contains(s.getSection().getSectionId())
                        && s.getSemester() != null
                        && !courseSemId.equals(s.getSemester().getSemesterId()))
                .findFirst().orElse(null);
        if (wrongSem != null) {
            BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                    () -> attendanceService.markAttendance(r.sessionId(),
                            new MarkAttendanceRequest(List.of(
                                    new MarkAttendanceRequest.AttendanceEntry(
                                            wrongSem.getStudentId(),
                                            com.unicconnect.entity.AttendanceStatus.PRESENT,
                                            null,
                                            slotAt(0).getSlotId(),
                                            slotAt(Math.min(1, spanPeriods - 1)).getSlotId())))));
            assertTrue(ex.getMessage().contains("cohort"), ex.getMessage());
        }

        // right semester, UNCOVERED section -> must be rejected
        Student wrongSec = studentRepository.findAll().stream()
                .filter(s -> s.getSemester() != null
                        && courseSemId.equals(s.getSemester().getSemesterId())
                        && (s.getSection() == null
                            || !ClassScheduleService.coveredSections(schedule)
                                    .contains(s.getSection().getSectionId())))
                .findFirst().orElse(null);
        if (wrongSec != null) {
            BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                    () -> attendanceService.markAttendance(r.sessionId(),
                            new MarkAttendanceRequest(List.of(
                                    new MarkAttendanceRequest.AttendanceEntry(
                                            wrongSec.getStudentId(),
                                            com.unicconnect.entity.AttendanceStatus.PRESENT,
                                            null,
                                            slotAt(0).getSlotId(),
                                            slotAt(0).getSlotId())))));
            assertTrue(ex.getMessage().contains("cohort"), ex.getMessage());
        }
    }

    // ===== SESSION DELETE =====

    @Test
    void authorizedLecturerCanDeleteSession_andAttendanceGoesWithIt() {
        Roster r = freshRoster();
        UUID stu = r.studentIds().get(0);
        attendanceService.markAttendance(r.sessionId(), new MarkAttendanceRequest(List.of(
                entry(stu, true, 0, spanPeriods - 1))));
        assertEquals(spanPeriods,
                attendanceRepository.findBySession_SessionId(r.sessionId())
                        .stream().mapToInt(a -> AttendanceService.attendedPeriods(
                                a.getAttendanceStartSlot(), a.getAttendanceEndSlot())).sum());

        assertTrue(sessionRepository.findById(r.sessionId()).isPresent());
        rollCallService.deleteSession(r.sessionId(), lecturer);

        assertFalse(sessionRepository.findById(r.sessionId()).isPresent(),
                "session deleted");
        assertEquals(0, attendanceRepository.findBySession_SessionId(r.sessionId()).size(),
                "no orphan attendance rows");

        // deleted session no longer appears in history nor contributes to totals
        var hist = rollCallService.history(schedule.getScheduleId(),
                LocalDate.now().withDayOfMonth(1), LocalDate.now());
        assertTrue(hist.sessions().stream()
                .noneMatch(s -> s.sessionId().equals(r.sessionId())), "column removed");
    }

    @Test
    void unauthorizedLecturerCannotDeleteSession() {
        Roster r = freshRoster();
        Staff outsider = staffRepository.findAll().stream()
                .filter(st -> !ClassScheduleService.coveredStaff(schedule).contains(st.getStaffId()))
                .filter(st -> rollCallService.hasActivePosition(st, "LECTURER"))
                .filter(st -> st.getUser() != null)
                .findFirst().orElse(null);
        if (outsider == null) return;

        authAs(outsider);
        assertThrows(BusinessRuleException.class,
                () -> rollCallService.deleteSession(r.sessionId(), outsider));
        authAs(lecturer);
        assertTrue(sessionRepository.findById(r.sessionId()).isPresent(),
                "session untouched by unauthorized delete");
    }

    // ===== SESSION DATE RULE =====

    @Test
    void futureScheduledDateCreatesSessionOnThatDate_wrongWeekdayRejected() {
        // next occurrence matching this schedule's weekday
        int dow = schedule.getDayOfWeek();
        LocalDate target = LocalDate.now().plusDays(1);
        while (target.getDayOfWeek().getValue() != dow) target = target.plusDays(1);

        ClassSession s = rollCallService.ensureSessionOn(
                schedule.getScheduleId(), lecturer, target);
        assertEquals(target, s.getSessionDate(),
                "CLASS_SESSION.session_date must be the requested occurrence");

        // a different weekday can never own this schedule's session
        LocalDate wrong = target.plusDays(1);
        if (wrong.getDayOfWeek().getValue() != dow) {
            BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                    () -> rollCallService.ensureSessionOn(
                            schedule.getScheduleId(), lecturer, wrong));
            assertTrue(ex.getMessage().contains("does not match"), ex.getMessage());
        }
    }

    @Test
    void previousAndUpcomingOccurrencesBothMarkable_sessionDatePreserved() {
        int dow = schedule.getDayOfWeek();
        LocalDate upcoming = LocalDate.now().plusDays(1);
        while (upcoming.getDayOfWeek().getValue() != dow) upcoming = upcoming.plusDays(1);
        LocalDate previous = LocalDate.now().minusDays(7);
        while (previous.getDayOfWeek().getValue() != dow) previous = previous.minusDays(1);

        ClassSession up = rollCallService.ensureSessionOn(
                schedule.getScheduleId(), lecturer, upcoming);
        assertEquals(upcoming, up.getSessionDate(), "upcoming occurrence date preserved");
        ClassSession prev = rollCallService.ensureSessionOn(
                schedule.getScheduleId(), lecturer, previous);
        assertEquals(previous, prev.getSessionDate(),
                "PREVIOUS scheduled class entered late keeps its own date (not today)");

        Roster r = freshRoster();
        UUID stu = r.studentIds().get(0);

        // both occurrences are markable — previous entry is an explicit rule
        assertDoesNotThrow(() -> attendanceService.markAttendance(up.getSessionId(),
                new MarkAttendanceRequest(List.of(entry(stu, true, 0,
                        Math.max(0, spanPeriods - 1))))));
        assertDoesNotThrow(() -> attendanceService.markAttendance(prev.getSessionId(),
                new MarkAttendanceRequest(List.of(entry(stu, false, null, null)))));

        // monthly aggregation uses session_date months, never submission date
        var month = calculationService.monthly(stu, courseIdOfRow(),
                upcoming.getYear(), upcoming.getMonthValue());
        assertTrue(month.scheduledPeriods() >= spanPeriods,
                "upcoming session counted in ITS OWN month");
    }

    private UUID courseIdOfRow() {
        var ta = schedule.getTeachingAssignment();
        return ta != null ? ta.getCourse().getCourseId() : null;
    }

    private int rowAttended(com.unicconnect.dto.response.DailyAttendanceResponse d, UUID sid) {
        return d.students().stream().filter(r2 -> r2.studentId().equals(sid))
                .findFirst().orElseThrow().attendedPeriods();
    }

    private double rowPercent(com.unicconnect.dto.response.DailyAttendanceResponse d, UUID sid) {
        return d.students().stream().filter(r2 -> r2.studentId().equals(sid))
                .findFirst().orElseThrow().attendancePercent();
    }
}
