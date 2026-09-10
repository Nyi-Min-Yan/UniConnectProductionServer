package com.unicconnect.service;

import com.unicconnect.dto.response.StudentRollCallResponse;
import com.unicconnect.dto.response.StudentRollCallResponse.Course;
import com.unicconnect.dto.response.StudentRollCallResponse.Session;
import com.unicconnect.entity.AcademicTerm;
import com.unicconnect.entity.Attendance;
import com.unicconnect.entity.AttendanceStatus;
import com.unicconnect.entity.ClassSchedule;
import com.unicconnect.entity.ClassSession;
import com.unicconnect.entity.GenerationStatus;
import com.unicconnect.entity.ScheduleStatus;
import com.unicconnect.entity.ScheduleType;
import com.unicconnect.entity.Student;
import com.unicconnect.entity.TeachingAssignmentGroupMember;
import com.unicconnect.entity.TermStatus;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.AcademicTermRepository;
import com.unicconnect.repository.AttendanceRepository;
import com.unicconnect.repository.ClassScheduleRepository;
import com.unicconnect.repository.ClassSessionRepository;
import com.unicconnect.repository.StudentRepository;
import com.unicconnect.util.SecurityUtil;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class StudentRollCallService {

    private final StudentRepository studentRepository;
    private final AcademicTermRepository termRepository;
    private final ClassScheduleRepository classScheduleRepository;
    private final ClassSessionRepository classSessionRepository;
    private final AttendanceRepository attendanceRepository;
    private final SecurityUtil securityUtil;
    private final CurriculumEligibilityService curriculumEligibilityService;

    public StudentRollCallService(StudentRepository studentRepository,
                                  AcademicTermRepository termRepository,
                                  ClassScheduleRepository classScheduleRepository,
                                  ClassSessionRepository classSessionRepository,
                                  AttendanceRepository attendanceRepository,
                                  SecurityUtil securityUtil,
                                  CurriculumEligibilityService curriculumEligibilityService) {
        this.studentRepository = studentRepository;
        this.termRepository = termRepository;
        this.classScheduleRepository = classScheduleRepository;
        this.classSessionRepository = classSessionRepository;
        this.attendanceRepository = attendanceRepository;
        this.securityUtil = securityUtil;
        this.curriculumEligibilityService = curriculumEligibilityService;
    }

    public StudentRollCallResponse summary(UUID studentId) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("Student not found"));
        verifyStudentAccess(student);

        if (student.getSection() == null) {
            return new StudentRollCallResponse(student.getStudentId(), student.getRollNo(), student.getStudentName(),
                    student.getSemester() != null ? String.valueOf(student.getSemester().getSemesterNo()) : null,
                    null, null, null, List.of());
        }

        UUID termId = termRepository.findByStatus(TermStatus.ACTIVE).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No active academic term"))
                .getTermId();
        AcademicTerm activeTerm = termRepository.findById(termId)
                .orElseThrow(() -> new ResourceNotFoundException("Academic term not found"));

        LocalDate today = LocalDate.now();
        LocalDate start = activeTerm.getStartDate() != null ? activeTerm.getStartDate() : today.minusMonths(6);
        LocalDate termEnd = activeTerm.getEndDate();
        LocalDate end = termEnd == null || termEnd.isBefore(today)
                ? today.withDayOfMonth(today.lengthOfMonth())
                : termEnd;

        UUID studentSemesterId = student.getSemester() != null ? student.getSemester().getSemesterId() : null;
        UUID studentSectionId = student.getSection() != null ? student.getSection().getSectionId() : null;

        List<ClassSchedule> courses = classScheduleRepository.findByTermIdWithDetails(termId).stream()
                .filter(s -> s.getScheduleStatus() != ScheduleStatus.CANCELLED)
                .filter(s -> s.getGeneration().getStatus() == GenerationStatus.PUBLISHED)
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .filter(s -> {
                    com.unicconnect.entity.Course co = courseOf(s);
                    if (co == null) return false;
                    if (!curriculumEligibilityService.isVisibleToStudent(co, student)) return false;
                    return studentSemesterId == null || co.getSemester() == null
                            || studentSemesterId.equals(co.getSemester().getSemesterId());
                })
                .filter(s -> studentSectionId == null
                        || ClassScheduleService.coveredSections(s).contains(studentSectionId))
                .toList();

        List<UUID> scheduleIds = courses.stream().map(ClassSchedule::getScheduleId).toList();
        List<ClassSession> sessions = scheduleIds.isEmpty() ? List.of()
                : classSessionRepository.findBySchedule_ScheduleIdInAndSessionDateBetweenOrderBySessionDateAsc(
                        scheduleIds, start, end);

        Map<UUID, Attendance> attendanceBySession = new HashMap<>();
        for (Attendance a : attendanceRepository.findByStudent_StudentId(studentId)) {
            if (a.getSession() != null) {
                attendanceBySession.putIfAbsent(a.getSession().getSessionId(), a);
            }
        }

        Map<String, List<ClassSchedule>> groupedByCourse = new LinkedHashMap<>();
        for (ClassSchedule s : courses) {
            String code = courseCodeOf(s);
            if (code != null) {
                groupedByCourse.computeIfAbsent(code, k -> new ArrayList<>()).add(s);
            }
        }

        List<Course> result = groupedByCourse.values().stream()
                .map(schedules -> buildCourse(schedules, sessions, attendanceBySession, today, start, end))
                .sorted(Comparator.comparing(Course::courseCode))
                .toList();

        return new StudentRollCallResponse(student.getStudentId(), student.getRollNo(), student.getStudentName(),
                student.getSemester() != null ? String.valueOf(student.getSemester().getSemesterNo()) : null,
                student.getSection() != null ? student.getSection().getSectionName() : null,
                start, end, result);
    }

    private Course buildCourse(List<ClassSchedule> schedules,
                               List<ClassSession> sessions,
                               Map<UUID, Attendance> attendanceBySession,
                               LocalDate today,
                               LocalDate start,
                               LocalDate end) {
        ClassSchedule first = schedules.get(0);
        com.unicconnect.entity.Course course = courseOf(first);

        LinkedHashSet<String> sections = new LinkedHashSet<>();
        LinkedHashSet<String> staffNames = new LinkedHashSet<>();
        for (ClassSchedule s : schedules) {
            collectSections(s, sections);
            collectStaff(s, staffNames);
        }

        Map<String, ClassSession> sessionByKey = new HashMap<>();
        for (ClassSession ses : sessions) {
            if (ses.getSchedule() != null) {
                sessionByKey.put(key(ses.getSchedule().getScheduleId(), ses.getSessionDate()), ses);
            }
        }

        int planned = 0;
        int elapsed = 0;
        int held = 0;
        int present = 0;
        int absent = 0;
        boolean todayClass = false;
        String todayStartTime = null;
        String todayEndTime = null;
        String todayStatus = null;
        List<Session> rows = new ArrayList<>();

        for (ClassSchedule s : schedules) {
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                if (d.getDayOfWeek().getValue() != s.getDayOfWeek()) {
                    continue;
                }
                planned++;
                ClassSession ses = sessionByKey.get(key(s.getScheduleId(), d));
                Attendance att = null;
                if (ses != null) {
                    att = attendanceBySession.get(ses.getSessionId());
                }

                boolean isElapsed = !d.isAfter(today);
                if (isElapsed) {
                    elapsed++;
                }
                if (ses != null && isElapsed) {
                    held++;
                }
                if (att != null) {
                    if (att.getAttendanceStatus() == AttendanceStatus.PRESENT) {
                        present++;
                    } else {
                        absent++;
                    }
                }

                if (ses != null) {
                    String phase = d.isBefore(today) ? "PAST" : d.isEqual(today) ? "TODAY" : "UPCOMING";
                    rows.add(new Session(
                            ses.getSessionId(),
                            s.getScheduleId(),
                            d,
                            dayName(d),
                            startTime(s),
                            endTime(s),
                            scheduledPeriods(s),
                            phase,
                            att != null ? att.getAttendanceStatus().name() : null,
                            att != null && att.getAttendanceStatus() == AttendanceStatus.PRESENT
                                    ? AttendanceService.attendedPeriods(att.getAttendanceStartSlot(), att.getAttendanceEndSlot())
                                    : 0,
                            att != null ? att.getRemark() : null,
                            att != null && att.getMarkedByStaff() != null ? att.getMarkedByStaff().getStaffName() : null));
                }

                if (d.isEqual(today)) {
                    todayClass = true;
                    todayStartTime = startTime(s);
                    todayEndTime = endTime(s);
                    todayStatus = ses == null ? "NO_SESSION"
                            : (att != null ? att.getAttendanceStatus().name() : "UNMARKED");
                }
            }
        }
        rows.sort(Comparator.comparing(Session::sessionDate));

        int marked = present + absent;
        double attendancePct = marked > 0 ? present * 100.0 / marked : 0.0;
        boolean belowThreshold = marked > 0 && attendancePct < 75.0;
        int absencesAllowed = (int) Math.floor(planned * 0.25);
        int canMissMore = Math.max(0, absencesAllowed - absent);
        int needToAttend = marked > 0
                ? (int) Math.ceil(Math.max(0, 0.75 * marked - present) / 0.25) : 0;

        return new Course(
                courseCodeOf(first),
                courseNameOf(first),
                course != null && course.getSemester() != null ? course.getSemester().getSemesterNo() : null,
                new ArrayList<>(sections),
                new ArrayList<>(staffNames),
                planned, elapsed, held, present, absent,
                Math.max(0, held - marked),
                attendancePct, belowThreshold, canMissMore, needToAttend,
                todayClass, todayStartTime, todayEndTime, todayStatus,
                rows);
    }

    private void verifyStudentAccess(Student student) {
        if (securityUtil.isAdmin() || securityUtil.isStaff()) {
            return;
        }
        UUID currentUserId = securityUtil.currentUserId();
        if (student.getUser() == null || !student.getUser().getUserId().equals(currentUserId)) {
            throw new AccessDeniedException("You are not authorized to view this student's roll call");
        }
    }

    private static String key(UUID scheduleId, LocalDate date) {
        return scheduleId + "|" + date;
    }

    private static com.unicconnect.entity.Course courseOf(ClassSchedule s) {
        if (s.getTeachingAssignment() != null) {
            return s.getTeachingAssignment().getCourse();
        }
        if (s.getTeachingGroup() != null) {
            return s.getTeachingGroup().getCourse();
        }
        return null;
    }

    private static String courseCodeOf(ClassSchedule s) {
        com.unicconnect.entity.Course c = courseOf(s);
        return c != null ? c.getCourseCode() : null;
    }

    private static String courseNameOf(ClassSchedule s) {
        com.unicconnect.entity.Course c = courseOf(s);
        return c != null ? c.getCourseName() : null;
    }

    private static void collectSections(ClassSchedule s, Set<String> out) {
        if (s.getTeachingAssignment() != null) {
            if (s.getTeachingAssignment().getSection() != null) {
                out.add(s.getTeachingAssignment().getSection().getSectionName());
            }
        } else if (s.getTeachingGroup() != null) {
            for (TeachingAssignmentGroupMember m : s.getTeachingGroup().getMembers()) {
                if (m.getAssignment().getSection() != null) {
                    out.add(m.getAssignment().getSection().getSectionName());
                }
            }
        }
    }

    private static void collectStaff(ClassSchedule s, Set<String> out) {
        if (s.getTeachingAssignment() != null) {
            if (s.getTeachingAssignment().getStaff() != null) {
                out.add(s.getTeachingAssignment().getStaff().getStaffName());
            }
        } else if (s.getTeachingGroup() != null) {
            for (TeachingAssignmentGroupMember m : s.getTeachingGroup().getMembers()) {
                if (m.getAssignment().getStaff() != null) {
                    out.add(m.getAssignment().getStaff().getStaffName());
                }
            }
        }
    }

    private static String dayName(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ENGLISH);
    }

    private static String startTime(ClassSchedule s) {
        return s.getStartSlot() != null && s.getStartSlot().getStartTime() != null
                ? s.getStartSlot().getStartTime().toString() : null;
    }

    private static String endTime(ClassSchedule s) {
        return s.getEndSlot() != null && s.getEndSlot().getEndTime() != null
                ? s.getEndSlot().getEndTime().toString() : null;
    }

    private static Integer scheduledPeriods(ClassSchedule s) {
        if (s.getStartSlot() == null || s.getEndSlot() == null) {
            return null;
        }
        return s.getEndSlot().getPeriodNo() - s.getStartSlot().getPeriodNo() + 1;
    }
}