package com.unicconnect.service;

import com.unicconnect.dto.request.CreateGenerationRequest;
import com.unicconnect.dto.request.GenerateTimetableRequest;
import com.unicconnect.dto.response.ScheduleResponse;
import com.unicconnect.entity.AssignmentStatus;
import com.unicconnect.entity.ClassSchedule;
import com.unicconnect.entity.Course;
import com.unicconnect.entity.CourseMeetingRequirement;
import com.unicconnect.entity.GenerationStatus;
import com.unicconnect.entity.MeetingType;
import com.unicconnect.entity.ScheduleType;
import com.unicconnect.entity.TeachingAssignment;
import com.unicconnect.repository.AcademicTermRepository;
import com.unicconnect.repository.ClassScheduleRepository;
import com.unicconnect.repository.CourseMeetingRequirementRepository;
import com.unicconnect.repository.CourseRepository;
import com.unicconnect.repository.SectionRepository;
import com.unicconnect.repository.TeachingAssignmentRepository;
import com.unicconnect.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * READ-ONLY verification of the elective co-location rule after the curriculum
 * binding changes. Every scenario runs inside the rolled-back test transaction:
 * no production data is modified.
 *
 * Elective combination (multiple course codes, one timetable cell) and
 * TeachingAssignmentGroup (one course, multiple sections) are verified as two
 * separate mechanisms.
 */
@SpringBootTest
@Transactional
public class ElectiveCombinationVerificationTest {

    private static final UUID TERM_ID = UUID.fromString("6eea6860-074e-4e8a-973d-7a538325bef1");
    private static final UUID SEM6 = UUID.fromString("a5c879b7-1f99-48f3-a1ab-8c053e37e154");
    private static final UUID SEM7 = UUID.fromString("81e92a47-dce7-4a86-9975-f82fb3d03cfb");
    private static final UUID SEC_A = UUID.fromString("c183c6d5-9810-40c7-8d93-7636c2c295fe");
    private static final UUID SEC_CT = UUID.fromString("ab433047-66c0-42ca-b206-294ec27db8cc");

    private static final String E_CS_3215 = "CS-3215";
    private static final String E_CST_3217 = "CST-3217";
    private static final String E_CST_3258 = "CST-3258";
    private static final List<String> SEM6_ELECTIVES = List.of(E_CS_3215, E_CST_3217, E_CST_3258);

    @Autowired
    TimetableGenerationService generationService;

    @Autowired
    TeachingAssignmentRepository assignmentRepository;

    @Autowired
    ClassScheduleRepository scheduleRepository;

    @Autowired
    CourseRepository courseRepository;

    @Autowired
    CourseMeetingRequirementRepository requirementRepository;

    @Autowired
    SectionRepository sectionRepository;

    @Autowired
    AcademicTermRepository termRepository;

    @Autowired
    UserRepository userRepository;

    @BeforeEach
    void authenticateAsHod() {
        UUID userId = userRepository.findByEmail("dawmya@gmail.com")
                .orElseThrow(() -> new IllegalStateException("test HOD user missing"))
                .getUserId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }

    // ========== helpers ==========

    private UUID generate(List<GenerateTimetableRequest.SemesterSelection> selections,
                          boolean autoBind) {
        UUID generationId = generationService.create(new CreateGenerationRequest(TERM_ID, null)).generationId();
        generationService.generate(generationId,
                new GenerateTimetableRequest(null, selections, autoBind));
        var done = generationService.runGenerationBackground(generationId);
        assertEquals(GenerationStatus.COMPLETED, done.status(),
                "elective scenario generation must complete");
        return generationId;
    }

    private void setCmr(String courseCode, UUID semesterId, String... rows) {
        Course course = courseRepository.findBySemester_SemesterId(semesterId).stream()
                .filter(c -> c.getCourseCode().equals(courseCode))
                .findFirst().orElseThrow(() -> new IllegalStateException("missing course " + courseCode));
        requirementRepository.deleteAll(requirementRepository.findByCourse_CourseId(course.getCourseId()));
        for (String row : rows) {
            String[] parts = row.split(":");
            CourseMeetingRequirement req = new CourseMeetingRequirement();
            req.setCourse(course);
            req.setMeetingType(MeetingType.valueOf(parts[0]));
            req.setSessionsPerWeek(Integer.parseInt(parts[1]));
            req.setPeriodsPerSession(Integer.parseInt(parts[2]));
            requirementRepository.save(req);
        }
    }

    /** Manual elective delivery reusing the lecturer who teaches it on section A. */
    private TeachingAssignment bindElective(String courseCode, UUID semesterId, UUID sectionId) {
        Course course = courseRepository.findBySemester_SemesterId(semesterId).stream()
                .filter(c -> c.getCourseCode().equals(courseCode))
                .findFirst().orElseThrow(() -> new IllegalStateException("missing course " + courseCode));
        TeachingAssignment template = assignmentRepository.findWithDetailsByTermId(TERM_ID).stream()
                .filter(a -> a.getAssignmentStatus() != AssignmentStatus.CANCELLED)
                .filter(a -> a.getCourse().getCourseId().equals(course.getCourseId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "no template assignment for " + courseCode));
        TeachingAssignment assignment = new TeachingAssignment();
        assignment.setCourse(course);
        assignment.setStaff(template.getStaff());
        assignment.setSection(sectionRepository.findById(sectionId).orElseThrow());
        assignment.setTerm(termRepository.findById(TERM_ID).orElseThrow());
        assignment.setAssignmentStatus(AssignmentStatus.ACTIVE);
        assignment.setAssignedAt(Instant.now());
        return assignmentRepository.save(assignment);
    }

    /** Removes persistent SEC_CT deliveries (earlier live runs); rolled back after the test. */
    private void pruneSecCtAssignments() {
        List<TeachingAssignment> stale = assignmentRepository
                .findWithDetailsByTermId(TERM_ID).stream()
                .filter(a -> SEC_CT.equals(a.getSection().getSectionId()))
                .toList();
        if (stale.isEmpty()) return;
        Set<UUID> staleIds = new HashSet<>();
        for (TeachingAssignment a : stale) staleIds.add(a.getAssignmentId());
        List<ClassSchedule> linked = scheduleRepository
                .findBySectionIdWithDetails(SEC_CT).stream()
                .filter(s -> s.getTeachingAssignment() != null
                        && staleIds.contains(s.getTeachingAssignment().getAssignmentId()))
                .toList();
        scheduleRepository.deleteAll(linked);
        assignmentRepository.deleteAll(stale);
    }

    private List<ClassSchedule> courseRows(UUID generationId, String... codes) {
        Set<String> wanted = Set.of(codes);
        return scheduleRepository.findByGeneration_GenerationId(generationId).stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .filter(s -> {
                    String cc = ClassScheduleService.courseCodeOf(s);
                    return cc != null && wanted.contains(cc);
                })
                .toList();
    }

    private static String windowKey(ClassSchedule s) {
        return s.getDayOfWeek() + "#" + s.getStartSlot().getDisplayOrder()
                + "-" + s.getEndSlot().getDisplayOrder();
    }

    private static int span(ClassSchedule s) {
        return s.getEndSlot().getDisplayOrder() - s.getStartSlot().getDisplayOrder() + 1;
    }

    // ========== TEST 1 + TEST 2: same-semester electives share one period ==========

    @Test
    void t1_sameSemesterElectivesCoLocateOnOneWindow() {
        SEM6_ELECTIVES.forEach(code -> setCmr(code, SEM6, "LECTURE:1:1"));
        UUID gid = generate(List.of(new GenerateTimetableRequest.SemesterSelection(SEM6, List.of(SEC_A))),
                false);

        List<ClassSchedule> rows = courseRows(gid, SEM6_ELECTIVES.toArray(String[]::new));
        assertEquals(3, rows.size(), "each elective must produce exactly one session here");
        Set<String> windows = new HashSet<>();
        rows.forEach(r -> windows.add(windowKey(r)));
        assertEquals(1, windows.size(),
                "all three same-semester electives must co-locate on ONE identical window, got " + windows);
    }

    /** TEST 2 (three electives) + TEST 7 (API DTO exposes the combined cell). */
    @Test
    void t2_apiDtosExposeOneCombinedCellForThreeElectives() {
        SEM6_ELECTIVES.forEach(code -> setCmr(code, SEM6, "LECTURE:1:1"));
        UUID gid = generate(List.of(new GenerateTimetableRequest.SemesterSelection(SEM6, List.of(SEC_A))),
                false);

        // The exact DTO the UI grid consumes (GET /api/generations/{id}/schedules).
        List<ScheduleResponse> dto = generationService.getSchedules(gid);
        Set<String> electiveWindows = new HashSet<>();
        dto.stream()
                .filter(r -> r.scheduleType() == ScheduleType.COURSE)
                .filter(r -> SEM6_ELECTIVES.contains(r.courseCode()))
                .forEach(r -> electiveWindows.add(
                        r.dayOfWeek() + "#" + r.startPeriodNo() + "-" + r.endPeriodNo()));
        assertEquals(1, electiveWindows.size(),
                "API must return the three electives under one identical day+period window");
        long distinctCodes = dto.stream()
                .filter(r -> r.scheduleType() == ScheduleType.COURSE)
                .filter(r -> SEM6_ELECTIVES.contains(r.courseCode()))
                .map(ScheduleResponse::courseCode)
                .distinct()
                .count();
        assertEquals(3, distinctCodes,
                "the combined cell carries three DIFFERENT course codes (rendered 'A / B / C' by the UI)");
    }

    // ========== TEST 3: two consecutive periods ==========

    @Test
    void t3_twoConsecutivePeriodSharing() {
        SEM6_ELECTIVES.forEach(code -> setCmr(code, SEM6, "LECTURE:1:2"));
        UUID gid = generate(List.of(new GenerateTimetableRequest.SemesterSelection(SEM6, List.of(SEC_A))),
                false);

        List<ClassSchedule> rows = courseRows(gid, SEM6_ELECTIVES.toArray(String[]::new));
        assertEquals(3, rows.size());
        Set<String> windows = new HashSet<>();
        rows.forEach(r -> {
            windows.add(windowKey(r));
            assertEquals(2, span(r), "the shared window must be TWO consecutive periods");
        });
        assertEquals(1, windows.size(),
                "all three electives must share the SAME two-consecutive-period window, got " + windows);
    }

    // ========== TEST 4: different semesters never combine ==========

    @Test
    void t4_differentSemestersNeverCombine() {
        // Sem6 group: 1x1 windows. Sem7 group: 1x2 windows. Different spans make any
        // cross-semester merge impossible; each semester's members still co-locate.
        SEM6_ELECTIVES.forEach(code -> setCmr(code, SEM6, "LECTURE:1:1"));
        setCmr("CS-4115", SEM7, "LECTURE:1:2");
        setCmr("CST-4137", SEM7, "LECTURE:1:2");
        setCmr("CST-4158", SEM7, "LECTURE:1:2");
        UUID gid = generate(List.of(
                new GenerateTimetableRequest.SemesterSelection(SEM6, List.of(SEC_A)),
                new GenerateTimetableRequest.SemesterSelection(SEM7, List.of(SEC_A))),
                false);

        List<ClassSchedule> sem6 = courseRows(gid, SEM6_ELECTIVES.toArray(String[]::new));
        List<ClassSchedule> sem7 = courseRows(gid, "CS-4115", "CST-4137", "CST-4158");
        assertEquals(3, sem6.size());
        assertEquals(3, sem7.size());

        Set<String> sem6Windows = new HashSet<>();
        sem6.forEach(r -> {
            sem6Windows.add(windowKey(r));
            assertEquals(1, span(r), "Sem6 elective span must stay 1 period");
        });
        assertEquals(1, sem6Windows.size(), "Sem6 trio co-locates among itself");

        Set<String> sem7Windows = new HashSet<>();
        sem7.forEach(r -> {
            sem7Windows.add(windowKey(r));
            assertEquals(2, span(r), "Sem7 elective span must stay 2 periods");
        });
        assertEquals(1, sem7Windows.size(), "Sem7 trio co-locates among itself");

        for (String w : sem7Windows) {
            assertTrue(!sem6Windows.contains(w),
                    "a semester-6 elective may never occupy the exact combined window of a semester-7 elective");
        }
    }

    // ========== TEST 5: required courses never join an elective cell ==========

    @Test
    void t5_requiredCourseNeverSharesElectiveWindow() {
        SEM6_ELECTIVES.forEach(code -> setCmr(code, SEM6, "LECTURE:1:1"));
        UUID gid = generate(List.of(new GenerateTimetableRequest.SemesterSelection(SEM6, List.of(SEC_A))),
                false);

        List<ClassSchedule> electives = courseRows(gid, SEM6_ELECTIVES.toArray(String[]::new));
        List<ClassSchedule> required = scheduleRepository.findByGeneration_GenerationId(gid).stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .filter(s -> {
                    String cc = ClassScheduleService.courseCodeOf(s);
                    return cc != null && !SEM6_ELECTIVES.contains(cc);
                })
                .toList();
        assertTrue(!required.isEmpty(), "scenario must contain required courses to contrast against");
        for (ClassSchedule req : required) {
            for (ClassSchedule elec : electives) {
                boolean sameDay = req.getDayOfWeek().equals(elec.getDayOfWeek());
                boolean overlaps = req.getStartSlot().getDisplayOrder() <= elec.getEndSlot().getDisplayOrder()
                        && elec.getStartSlot().getDisplayOrder() <= req.getEndSlot().getDisplayOrder();
                assertTrue(!(sameDay && overlaps),
                        "required course must never overlap an elective co-location window");
            }
        }
    }

    // ========== TEST 6: eligibility of combined electives ==========

    @Test
    void t6_ineligibleElectiveIsRejectedBeforeItCanCoLocate() {
        // CS-3215 is CS-only; CT students are NOT eligible. Even when bound to
        // SEC_CT manually, generation must fail closed - an ineligible elective
        // can therefore never share a cell with CT-eligible electives.
        pruneSecCtAssignments();
        bindElective(E_CS_3215, SEM6, SEC_CT);
        bindElective(E_CST_3217, SEM6, SEC_CT);
        setCmr(E_CS_3215, SEM6, "LECTURE:1:1");
        setCmr(E_CST_3217, SEM6, "LECTURE:1:1");

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.unicconnect.exception.BusinessRuleException.class,
                () -> generate(List.of(new GenerateTimetableRequest.SemesterSelection(SEM6, List.of(SEC_CT))),
                        true));
        assertTrue(ex.getMessage().contains("CS-3215 must not be assigned to CT section"),
                "generation must reject the CS-only elective on the CT cohort: " + ex.getMessage());
    }

    // ========== TEST 8: capacity reduction through co-location ==========

    @Test
    void t8_coLocationReducesPhysicalLoadAndProtectsRequiredCourses() {
        // Required Sem6/CT load: 6 courses x 4p = 24p (auto-bound).
        // Two CT-eligible electives at 2 sessions x 2p each: uncombined they need
        // 8 extra periods (24+8=32 > 30 slots). Co-located they need 4 (28 <= 30).
        pruneSecCtAssignments();
        List.of(E_CST_3217, E_CST_3258).forEach(code -> {
            bindElective(code, SEM6, SEC_CT);
            setCmr(code, SEM6, "LECTURE:2:2");
        });
        UUID gid = generate(List.of(new GenerateTimetableRequest.SemesterSelection(SEM6, List.of(SEC_CT))),
                true);

        List<ClassSchedule> electives = courseRows(gid, E_CST_3217, E_CST_3258);
        assertEquals(4, electives.size(), "2 electives x 2 sessions each");
        Set<String> windows = new HashSet<>();
        electives.forEach(r -> windows.add(windowKey(r)));
        assertEquals(2, windows.size(),
                "the group must collapse onto exactly 2 shared windows (2 occurrences), got " + windows);
        int electivePeriods = windows.stream().mapToInt(w -> Integer.parseInt(w.split("-")[1])
                - Integer.parseInt(w.split("#")[1].split("-")[0]) + 1).sum();
        assertEquals(4, electivePeriods,
                "physical elective load must be 4 periods, not 12 - that is the capacity saving");

        // Mandatory curriculum stays complete: all six required Sem6/CT courses present.
        Set<String> codes = new HashSet<>();
        scheduleRepository.findByGeneration_GenerationId(gid).stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .forEach(s -> {
                    String cc = ClassScheduleService.courseCodeOf(s);
                    if (cc != null) codes.add(cc);
                });
        for (String required : List.of("CT-3231", "CT-3232", "CT-3233", "CT-3235", "CST-3226", "CST-3254")) {
            assertTrue(codes.contains(required), "mandatory course " + required + " must remain scheduled");
        }
    }
}
