package com.unicconnect.service;

import com.unicconnect.dto.request.CreateGenerationRequest;
import com.unicconnect.dto.request.GenerateTimetableRequest;
import com.unicconnect.entity.ClassSchedule;
import com.unicconnect.entity.Course;
import com.unicconnect.entity.CourseMeetingRequirement;
import com.unicconnect.entity.GenerationStatus;
import com.unicconnect.entity.MeetingType;
import com.unicconnect.entity.ScheduleStatus;
import com.unicconnect.entity.ScheduleType;
import com.unicconnect.entity.TeachingAssignment;
import com.unicconnect.repository.AcademicTermRepository;
import com.unicconnect.repository.ClassScheduleRepository;
import com.unicconnect.repository.CourseMeetingRequirementRepository;
import com.unicconnect.repository.GenerationSessionRepository;
import com.unicconnect.repository.TeachingAssignmentRepository;
import com.unicconnect.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Soft weekday-balance suite: COURSE periods per section must be distributed
 * across Monday-Friday instead of being packed into the earliest days.
 * Balance is a preference (SSD penalty in evaluatePlacement) - every hard
 * constraint from the P1-P5 work remains enforced.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class WeekdayBalanceTest {

    private static final UUID TERM_ID = UUID.fromString("6eea6860-074e-4e8a-973d-7a538325bef1");
    private static final UUID SEM1 = UUID.fromString("a0b04279-56ec-4f3f-876d-dde5ecba7fe4");
    private static final UUID SEM4 = UUID.fromString("2cbe4a58-543a-43fa-9cf8-c3dc1be5d749");
    private static final UUID SEC_A = UUID.fromString("c183c6d5-9810-40c7-8d93-7636c2c295fe");
    private static final UUID SEC_CT = UUID.fromString("ab433047-66c0-42ca-b206-294ec27db8cc");

    @Autowired
    TimetableGenerationService generationService;
    @Autowired
    AcademicTermRepository termRepository;
    @Autowired
    GenerationSessionRepository generationSessionRepository;
    @Autowired
    TeachingAssignmentRepository assignmentRepository;
    @Autowired
    ClassScheduleRepository scheduleRepository;
    @Autowired
    CourseMeetingRequirementRepository requirementRepository;
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

    private void setCmr(String courseCode, UUID sectionId, String... rows) {
        Course course = assignmentFor(courseCode, sectionId).getCourse();
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
        requirementRepository.flush();
    }

    private UUID generate(UUID semesterId, List<UUID> sectionIds) {
          UUID generationId = generationService.create(new CreateGenerationRequest(TERM_ID, null)).generationId();
          generationService.generate(generationId,
                new GenerateTimetableRequest(null,
                        List.of(new GenerateTimetableRequest.SemesterSelection(semesterId, sectionIds)),
                        false));
        // The heavy worker normally runs on generationExecutor behind an
        // afterCommit hook that never fires inside this rolled-back test
        // transaction; drive it directly to exercise the identical path.
        var done = generationService.runGenerationBackground(generationId);
        assertEquals(GenerationStatus.COMPLETED, done.status(), "generation must complete");
        return generationId;
    }

    /**
     * SEC_CT carries persistent deliveries created by earlier live runs; solver
     * scenarios must see only the courses they stage themselves. Deletions run
     * inside the rolled-back test transaction, so no data is harmed.
     */
    private void pruneSecCtAssignmentsExcept(String... keepCodes) {
        Set<String> keep = Set.of(keepCodes);
        List<TeachingAssignment> stale = assignmentRepository
                .findWithDetailsByTermId(TERM_ID).stream()
                .filter(a -> SEC_CT.equals(a.getSection().getSectionId()))
                .filter(a -> !keep.contains(a.getCourse().getCourseCode()))
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

    private List<ClassSchedule> courseOnly(List<ClassSchedule> all) {
        return all.stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .filter(s -> s.getScheduleStatus() != ScheduleStatus.CANCELLED)
                .toList();
    }

    private List<ClassSchedule> inSection(List<ClassSchedule> all, UUID sectionId) {
        return courseOnly(all).stream()
                .filter(s -> ClassScheduleService.coveredSections(s).contains(sectionId))
                .toList();
    }

    private int length(ClassSchedule s) {
        return s.getEndSlot().getDisplayOrder() - s.getStartSlot().getDisplayOrder() + 1;
    }

    private TeachingAssignment assignmentFor(String courseCode, UUID sectionId) {
        return assignmentRepository.findWithDetailsByTermId(TERM_ID).stream()
                .filter(a -> a.getCourse().getCourseCode().equals(courseCode))
                .filter(a -> a.getSection().getSectionId().equals(sectionId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("assignment not found: " + courseCode));
    }

    /** COURSE periods per day (1..5); LMS/ASSIGNMENT/BREAK excluded by construction. */
    private int[] courseLoads(List<ClassSchedule> scheds) {
        int[] loads = new int[6];
        for (ClassSchedule s : scheds) {
            loads[s.getDayOfWeek()] += length(s);
        }
        return loads;
    }

    private int maxMin(int[] loads) {
        int min = Integer.MAX_VALUE, max = 0;
        for (int d = 1; d <= 5; d++) {
            min = Math.min(min, loads[d]);
            max = Math.max(max, loads[d]);
        }
        return max - min;
    }

    // ========== T1: distribution across Monday-Friday ==========

    @Test
    void t1_coursePeriodsSpreadAcrossTheWeek() {
        List<ClassSchedule> all = courseOnly(scheduleRepository.findByGeneration_GenerationId(generate(SEM1, List.of(SEC_A))));
        int[] loads = courseLoads(inSection(all, SEC_A));
        for (int d = 1; d <= 5; d++) {
            assertTrue(loads[d] >= 2, "day " + d + " must carry at least one full COURSE session, got "
                    + loads[d] + " periods");
        }
        assertTrue(loads[5] >= 2, "Friday must not be left with only LMS/ASSIGNMENT, got "
                + loads[5] + " COURSE periods");
    }

    // ========== T2: COURSE activity on all five weekdays ==========

    @Test
    void t2_courseActivityEveryWeekday() {
        List<ClassSchedule> all = courseOnly(scheduleRepository.findByGeneration_GenerationId(generate(SEM1, List.of(SEC_A))));
        List<ClassSchedule> sec = inSection(all, SEC_A);
        for (int d = 1; d <= 5; d++) {
            final int day = d;
            long sessions = sec.stream().filter(s -> s.getDayOfWeek() == day).count();
            assertTrue(sessions >= 1, "day " + d + " must have at least one COURSE session, got " + sessions);
        }
    }

    // ========== T3: reasonably balanced daily COURSE loads ==========

    @Test
    void t3_dailyCourseLoadsBalanced() {
        List<ClassSchedule> all = courseOnly(scheduleRepository.findByGeneration_GenerationId(generate(SEM1, List.of(SEC_A))));
        int[] loads = courseLoads(inSection(all, SEC_A));
        int total = 0;
        for (int d = 1; d <= 5; d++) total += loads[d];
        assertEquals(28, total, "Sem1/A must schedule all 28 COURSE periods");
        assertTrue(maxMin(loads) <= 4, "daily COURSE loads must be within one 2-period session of balanced, got "
                + java.util.Arrays.toString(loads));
    }

    // ========== T4: no exact-equality requirement ==========

    @Test
    void t4_exactEqualityNotRequired() {
        // 28 periods over 5 days cannot split equally (28 % 5 != 0); the solver
        // must still complete with a balanced-but-not-equal distribution.
        List<ClassSchedule> all = courseOnly(scheduleRepository.findByGeneration_GenerationId(generate(SEM1, List.of(SEC_A))));
        int[] loads = courseLoads(inSection(all, SEC_A));
        assertEquals(28, loads[1] + loads[2] + loads[3] + loads[4] + loads[5]);
        assertTrue(maxMin(loads) <= 4, "balanced tolerance, not equality: " + java.util.Arrays.toString(loads));
    }

    // ========== T5: scoring - compacted week scores worse than balanced week ==========

    @Test
    void t5_compactedWeekScoresWorse() {
        int[] compacted = {0, 8, 7, 5, 0, 0};   // index 0 unused; Mon=8, Tue=7, Wed=5, Thu=0, Fri=0
        int[] balanced = {0, 4, 4, 4, 4, 4};
        int[] nearBalanced = {0, 6, 6, 6, 6, 4};
        int pCompact = TimetableGenerationService.dayImbalancePenalty(compacted);
        int pBalanced = TimetableGenerationService.dayImbalancePenalty(balanced);
        int pNear = TimetableGenerationService.dayImbalancePenalty(nearBalanced);
        assertEquals(0, pBalanced, "perfectly balanced week must score 0");
        assertTrue(pCompact > pNear, "8,7,5,0,0 must score worse than 6,6,6,6,4 (" + pCompact + " vs " + pNear + ")");
        assertTrue(pNear > 0, "6,6,6,6,4 must still be slightly imbalanced");
        assertTrue(pCompact > 10 * pNear, "8,7,5,0,0 must score significantly worse than a near-balanced week");
        int[] exBalanced = {0, 4, 4, 3, 3, 4};
        int[] exCompacted = {0, 8, 7, 3, 0, 0};
        int pExBalanced = TimetableGenerationService.dayImbalancePenalty(exBalanced);
        int pExCompacted = TimetableGenerationService.dayImbalancePenalty(exCompacted);
        assertEquals(1, pExBalanced, "[4,4,3,3,4] must score 1, got " + pExBalanced);
        assertEquals(57, pExCompacted, "[8,7,3,0,0] must score 57, got " + pExCompacted);
        assertTrue(pExCompacted > 50 * pExBalanced,
                "[8,7,3,0,0] must be strongly dispreferred over [4,4,3,3,4] (" + pExCompacted + " vs " + pExBalanced + ")");
    }

    // ========== T6: LMS/ASSIGNMENT never count toward balance ==========

    @Test
    void t6_lmsAssignmentExcludedFromBalance() {
        List<ClassSchedule> all = scheduleRepository.findByGeneration_GenerationId(generate(SEM1, List.of(SEC_A)));
        long specials = all.stream()
                .filter(s -> s.getScheduleType() == ScheduleType.LMS || s.getScheduleType() == ScheduleType.ASSIGNMENT)
                .count();
        assertTrue(specials > 0, "the existing LMS/ASSIGNMENT mechanism must still generate special periods");
        int[] loads = courseLoads(inSection(all, SEC_A));
        int total = 0;
        for (int d = 1; d <= 5; d++) total += loads[d];
        assertEquals(28, total, "balance must count exactly the 28 COURSE periods - specials excluded");
    }

    // ========== T7: same-course-day rule remains hard ==========

    @Test
    void t7_sameCourseSameDayForbidden() {
        List<ClassSchedule> all = courseOnly(scheduleRepository.findByGeneration_GenerationId(generate(SEM1, List.of(SEC_A))));
        List<ClassSchedule> sec = inSection(all, SEC_A);
        for (int i = 0; i < sec.size(); i++) {
            for (int j = i + 1; j < sec.size(); j++) {
                ClassSchedule a = sec.get(i), b = sec.get(j);
                String codeA = ClassScheduleService.courseCodeOf(a);
                if (codeA != null && codeA.equals(ClassScheduleService.courseCodeOf(b))
                        && a.getDayOfWeek() == b.getDayOfWeek()) {
                    fail("course " + codeA + " has two sessions on day " + a.getDayOfWeek());
                }
            }
        }
    }

    // ========== T8: 2+1+1 still works and stays balanced ==========

    @Test
    void t8_twoPlusOnePlusOneBalanced() {
        setCmr("CST-1102", SEC_A, "LECTURE:1:2", "LECTURE:2:1");
        List<ClassSchedule> all = courseOnly(scheduleRepository.findByGeneration_GenerationId(generate(SEM1, List.of(SEC_A))));
        List<ClassSchedule> cst = inSection(all, SEC_A).stream()
                .filter(s -> "CST-1102".equals(ClassScheduleService.courseCodeOf(s)))
                .toList();
        assertEquals(3, cst.size(), "2+1+1 must produce 3 meetings");
        List<Integer> lens = new ArrayList<>();
        for (ClassSchedule s : cst) lens.add(length(s));
        lens.sort(Integer::compareTo);
        assertEquals(List.of(1, 1, 2), lens, "2+1+1 session lengths");
        Set<Integer> days = new HashSet<>();
        for (ClassSchedule s : cst) days.add(s.getDayOfWeek());
        assertEquals(3, days.size(), "2+1+1 components on 3 different days");
        int[] loads = courseLoads(inSection(all, SEC_A));
        assertEquals(28, loads[1] + loads[2] + loads[3] + loads[4] + loads[5]);
        assertTrue(maxMin(loads) <= 2, "28 periods over 5 days must be near 6/6/6/6/4, got "
                + java.util.Arrays.toString(loads));
    }

    // ========== T9: 1+1+1+1 still works and covers the week ==========

    @Test
    void t9_oneOneOneOneBalanced() {
        pruneSecCtAssignmentsExcept("CT-2234", "CT-2236");
        setCmr("CT-2236", SEC_CT, "LECTURE:4:1");
        List<ClassSchedule> all = courseOnly(scheduleRepository.findByGeneration_GenerationId(generate(SEM4, List.of(SEC_CT))));
        List<ClassSchedule> ct = inSection(all, SEC_CT).stream()
                .filter(s -> "CT-2236".equals(ClassScheduleService.courseCodeOf(s)))
                .toList();
        assertEquals(4, ct.size(), "1+1+1+1 must produce 4 meetings");
        Set<Integer> days = new HashSet<>();
        for (ClassSchedule s : ct) days.add(s.getDayOfWeek());
        assertEquals(4, days.size(), "1+1+1+1 components on 4 different days");
        int[] loads = courseLoads(inSection(all, SEC_CT));
        for (int d = 1; d <= 5; d++) {
            assertTrue(loads[d] >= 1, "Sem4/CT day " + d + " must have COURSE activity, got " + loads[d]);
        }
        assertTrue(maxMin(loads) <= 2, "Sem4/CT loads must stay balanced, got " + java.util.Arrays.toString(loads));
    }

    // ========== T10: Lecture 2 + Lab 2 still works ==========

    @Test
    void t10_lecTwoPlusLabTwoBalanced() {
        setCmr("CST-1102", SEC_A, "LECTURE:1:2", "LAB:1:2");
        List<ClassSchedule> all = courseOnly(scheduleRepository.findByGeneration_GenerationId(generate(SEM1, List.of(SEC_A))));
        List<ClassSchedule> cst = inSection(all, SEC_A).stream()
                .filter(s -> "CST-1102".equals(ClassScheduleService.courseCodeOf(s)))
                .toList();
        assertEquals(2, cst.size(), "LEC2+LAB2 must produce 2 meetings");
        for (ClassSchedule s : cst) assertEquals(2, length(s), "each meeting is 2 consecutive periods");
        Set<Integer> days = new HashSet<>();
        for (ClassSchedule s : cst) days.add(s.getDayOfWeek());
        assertEquals(2, days.size(), "LEC2+LAB2 components on 2 different days");
        int[] loads = courseLoads(inSection(all, SEC_A));
        assertTrue(maxMin(loads) <= 4, "Sem1/A must stay balanced, got " + java.util.Arrays.toString(loads));
    }

    // ========== T11: sparse section - 4 COURSE periods only ==========

    @Test
    void t11_sparseSectionFourPeriodsCompletes() {
        pruneSecCtAssignmentsExcept("CT-2234", "CT-2236");
        // Sem4/CT reduced to 4 COURSE periods in 4 sessions: two courses at 2x1.
        // 4 sessions cannot cover five weekdays - generation must still COMPLETE
        // without any hard five-day constraint.
        setCmr("CT-2234", SEC_CT, "LECTURE:2:1");
        setCmr("CT-2236", SEC_CT, "LECTURE:2:1");
        List<ClassSchedule> all = scheduleRepository.findByGeneration_GenerationId(generate(SEM4, List.of(SEC_CT)));
        List<ClassSchedule> sec = inSection(all, SEC_CT);
        assertEquals(4, sec.size(), "exactly 4 COURSE sessions must be generated");
        int total = 0;
        for (ClassSchedule s : sec) total += length(s);
        assertEquals(4, total, "exactly 4 COURSE periods must be generated");
        Set<Integer> days = new HashSet<>();
        for (ClassSchedule s : sec) days.add(s.getDayOfWeek());
        assertTrue(days.size() <= 4, "4 sessions cannot cover 5 days; no five-day hard rule may exist");
        long specials = all.stream()
                .filter(s -> s.getScheduleType() == ScheduleType.LMS || s.getScheduleType() == ScheduleType.ASSIGNMENT)
                .count();
        assertTrue(specials > 0, "LMS/ASSIGNMENT must still be placed in the free slots");
    }

    // ========== T12: multiple courses on one weekday ==========

    @Test
    void t12_multipleCoursesOnOneDayAllowed() {
        List<ClassSchedule> all = courseOnly(scheduleRepository.findByGeneration_GenerationId(generate(SEM1, List.of(SEC_A))));
        List<ClassSchedule> sec = inSection(all, SEC_A);
        boolean multipleOnOneDay = false;
        for (int d = 1; d <= 5; d++) {
            Set<String> codes = new HashSet<>();
            for (ClassSchedule s : sec) {
                if (s.getDayOfWeek() == d) codes.add(ClassScheduleService.courseCodeOf(s));
            }
            if (codes.size() >= 2) multipleOnOneDay = true;
        }
        assertTrue(multipleOnOneDay,
                "Sem1/A (14 sessions / 5 days) must legitimately place multiple different courses on one weekday");
    }
}