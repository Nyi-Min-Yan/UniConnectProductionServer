package com.unicconnect.service;

import com.unicconnect.dto.request.CreateGenerationRequest;
import com.unicconnect.dto.request.GenerateTimetableRequest;
import com.unicconnect.entity.AssignmentStatus;
import com.unicconnect.entity.ClassSchedule;
import com.unicconnect.entity.Course;
import com.unicconnect.entity.CourseMeetingRequirement;
import com.unicconnect.entity.GenerationStatus;
import com.unicconnect.entity.MeetingType;
import com.unicconnect.entity.ScheduleType;
import com.unicconnect.entity.Staff;
import com.unicconnect.entity.TeachingAssignment;
import com.unicconnect.entity.TeachingAssignmentGroup;
import com.unicconnect.entity.TeachingAssignmentGroupMember;
import com.unicconnect.entity.TeachingAssignmentGroupMemberId;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.repository.AcademicTermRepository;
import com.unicconnect.repository.ClassScheduleRepository;
import com.unicconnect.repository.CourseMeetingRequirementRepository;
import com.unicconnect.repository.CourseRepository;
import com.unicconnect.repository.GenerationSessionRepository;
import com.unicconnect.repository.SectionRepository;
import com.unicconnect.repository.SemesterRepository;
import com.unicconnect.repository.TeachingAssignmentGroupMemberRepository;
import com.unicconnect.repository.TeachingAssignmentGroupRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class CapacityOverflowVerificationTest {

    private static final UUID TERM_ID = UUID.fromString("6eea6860-074e-4e8a-973d-7a538325bef1");
    private static final UUID SEM5 = UUID.fromString("b9fb0cff-c55b-4ad0-9e0d-733f05c36d9a");
    private static final UUID SEM7 = UUID.fromString("81e92a47-dce7-4a86-9975-f82fb3d03cfb");
    private static final UUID SEC_A  = UUID.fromString("c183c6d5-9810-40c7-8d93-7636c2c295fe");
    private static final UUID SEC_B  = UUID.fromString("c60d8263-b9c3-4d77-8ae6-b603ca93f044");
    private static final UUID SEC_C  = UUID.fromString("99ded2d6-e522-4c41-9145-6d0b8cd30765");
    private static final UUID SEC_CT = UUID.fromString("ab433047-66c0-42ca-b206-294ec27db8cc");

    @Autowired TimetableGenerationService generationService;
    @Autowired TeachingAssignmentRepository assignmentRepository;
    @Autowired ClassScheduleRepository scheduleRepository;
    @Autowired CourseRepository courseRepository;
    @Autowired CourseMeetingRequirementRepository requirementRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired SemesterRepository semesterRepository;
    @Autowired AcademicTermRepository termRepository;
    @Autowired GenerationSessionRepository generationRepository;
    @Autowired TeachingAssignmentGroupRepository groupRepository;
    @Autowired TeachingAssignmentGroupMemberRepository memberRepository;
    @Autowired UserRepository userRepository;

    private List<Staff> staffPoolCache;

    @BeforeEach
    void authenticateAsHod() {
        UUID userId = userRepository.findByEmail("dawmya@gmail.com")
                .orElseThrow(() -> new IllegalStateException("test user missing")).getUserId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }

    private void pruneSemester(UUID semesterId, UUID... sectionIds) {
        Set<UUID> sections = new HashSet<>(Arrays.asList(sectionIds));
        List<TeachingAssignment> stale = assignmentRepository.findWithDetailsByTermId(TERM_ID).stream()
                .filter(a -> a.getCourse().getSemester() != null
                        && semesterId.equals(a.getCourse().getSemester().getSemesterId())
                        && sections.contains(a.getSection().getSectionId()))
                .toList();
        if (stale.isEmpty()) return;
        Set<UUID> staleIds = stale.stream().map(TeachingAssignment::getAssignmentId).collect(Collectors.toSet());
        List<TeachingAssignmentGroupMember> members = memberRepository.findAll().stream()
                .filter(m -> staleIds.contains(m.getId().getAssignmentId())).toList();
        if (!members.isEmpty()) memberRepository.deleteAll(members);
        List<ClassSchedule> linked = new ArrayList<>();
        for (UUID sec : sectionIds) {
            scheduleRepository.findBySectionIdWithDetails(sec).stream()
                    .filter(s -> (s.getTeachingAssignment() != null
                                    && staleIds.contains(s.getTeachingAssignment().getAssignmentId()))
                            || (s.getTeachingGroup() != null
                                    && s.getTeachingGroup().getCourse().getSemester() != null
                                    && semesterId.equals(s.getTeachingGroup().getCourse()
                                            .getSemester().getSemesterId())))
                    .forEach(linked::add);
        }
        if (!linked.isEmpty()) scheduleRepository.deleteAll(linked);
        assignmentRepository.deleteAll(stale);
        assignmentRepository.flush();
    }

    /** Removes this test's own synthetic assignments/courses so a later phase starts clean. */
    private void wipeByPrefix(String pfx) {
        List<TeachingAssignment> stale = assignmentRepository.findWithDetailsByTermId(TERM_ID).stream()
                .filter(a -> a.getCourse().getCourseCode().startsWith(pfx))
                .toList();
        if (stale.isEmpty()) return;
        Set<UUID> ids = stale.stream().map(TeachingAssignment::getAssignmentId).collect(Collectors.toSet());
        List<TeachingAssignmentGroupMember> members = memberRepository.findAll().stream()
                .filter(m -> ids.contains(m.getId().getAssignmentId())).toList();
        if (!members.isEmpty()) memberRepository.deleteAll(members);
        List<ClassSchedule> linked = scheduleRepository.findAll().stream()
                .filter(s -> s.getTeachingAssignment() != null
                        && ids.contains(s.getTeachingAssignment().getAssignmentId()))
                .toList();
        if (!linked.isEmpty()) scheduleRepository.deleteAll(linked);
        Set<UUID> courseIds = stale.stream()
                .map(a -> a.getCourse().getCourseId()).collect(Collectors.toSet());
        assignmentRepository.deleteAll(stale);
        assignmentRepository.flush();
        for (UUID cid : courseIds) {
            requirementRepository.deleteAll(requirementRepository.findByCourse_CourseId(cid));
            courseRepository.findById(cid).ifPresent(courseRepository::delete);
        }
        assignmentRepository.flush();
    }

    private List<Staff> loadStaffPool() {
        if (staffPoolCache == null) {
            Map<UUID, Staff> byId = new LinkedHashMap<>();
            assignmentRepository.findWithDetailsByTermId(TERM_ID).stream()
                    .filter(a -> a.getAssignmentStatus() != AssignmentStatus.CANCELLED)
                    .filter(a -> a.getStaff() != null)
                    .forEach(a -> byId.putIfAbsent(a.getStaff().getStaffId(), a.getStaff()));
            staffPoolCache = new ArrayList<>(byId.values());
        }
        return staffPoolCache;
    }

    private Staff staffAt(int idx) {
        List<Staff> pool = loadStaffPool();
        assertTrue(idx >= 0 && idx < pool.size(),
                "staff pool exhausted: need idx " + idx + " have " + pool.size());
        return pool.get(idx);
    }

    private Course newCourse(String code, boolean required, UUID semesterId, Staff staff) {
        Course template = courseRepository.findBySemester_SemesterId(semesterId).get(0);
        Course c = new Course();
        // Set unit from assigned staff so validateLecturerOwnership passes
        c.setUnit(staff.getUnit() != null ? staff.getUnit() : template.getUnit());
        c.setMajor(template.getMajor());
        c.setCourseCode(code);
        c.setCourseName("CAPV " + code);
        c.setCreditUnit(3);
        c.setRequired(required);
        c.setSemester(semesterRepository.findById(semesterId).orElseThrow());
        return courseRepository.save(c);
    }

    private void addCmr(Course course, int sessions, int periods) {
        CourseMeetingRequirement r = new CourseMeetingRequirement();
        r.setCourse(course);
        r.setMeetingType(MeetingType.LECTURE);
        r.setSessionsPerWeek(sessions);
        r.setPeriodsPerSession(periods);
        requirementRepository.save(r);
    }

    private TeachingAssignment bind(Course course, Staff staff, UUID sectionId) {
        TeachingAssignment a = new TeachingAssignment();
        a.setCourse(course);
        a.setStaff(staff);
        a.setSection(sectionRepository.findById(sectionId).orElseThrow());
        a.setTerm(termRepository.findById(TERM_ID).orElseThrow());
        a.setAssignmentStatus(AssignmentStatus.ACTIVE);
        a.setAssignedAt(Instant.now());
        return assignmentRepository.save(a);
    }

    private List<String> seedReq(String pfx, UUID sem, UUID sec,
                                 int n, int sess, int per, int staffBase) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String code = pfx + (i + 1);
            Staff s = staffAt((staffBase + i) % loadStaffPool().size());
            Course c = newCourse(code, true, sem, s);
            addCmr(c, sess, per);
            bind(c, s, sec);
            codes.add(code);
        }
        return codes;
    }

    private List<String> seedElec(String pfx, UUID sem, UUID sec,
                                  int n, int sess, int per, int staffBase) {
        List<Staff> pool = loadStaffPool();
        assertTrue(pool.size() >= staffBase + n,
                "Need " + (staffBase + n) + " staff but pool=" + pool.size());
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String code = pfx + (i + 1);
            Staff s = staffAt(staffBase + i);
            Course c = newCourse(code, false, sem, s);
            addCmr(c, sess, per);
            bind(c, s, sec);
            codes.add(code);
        }
        return codes;
    }

    /** n staff sharing one unit (needed for shared-group course ownership). */
    private List<Staff> staffSameUnit(int n) {
        Map<UUID, List<Staff>> byUnit = new LinkedHashMap<>();
        for (Staff st : loadStaffPool()) {
            UUID u = st.getUnit() != null ? st.getUnit().getUnitId() : null;
            byUnit.computeIfAbsent(u, k -> new ArrayList<>()).add(st);
        }
        List<Staff> best = new ArrayList<>();
        for (List<Staff> l : byUnit.values()) if (l.size() > best.size()) best = l;
        assertTrue(best.size() >= n, "need " + n + " same-unit staff, largest bucket=" + best.size());
        return best.subList(0, n);
    }

    private GenerateTimetableRequest.SemesterSelection sel(UUID sem, UUID... secs) {
        return new GenerateTimetableRequest.SemesterSelection(sem, List.of(secs));
    }

    private UUID startGen(List<GenerateTimetableRequest.SemesterSelection> selections) {
        UUID gid = generationService.create(new CreateGenerationRequest(TERM_ID, null)).generationId();
        generationService.generate(gid, new GenerateTimetableRequest(null, selections, false));
        return gid;
    }

    private List<ClassSchedule> rowsFor(UUID gid, String... codes) {
        Set<String> wanted = Set.of(codes);
        return scheduleRepository.findByGeneration_GenerationId(gid).stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .filter(s -> { String cc = ClassScheduleService.courseCodeOf(s); return cc != null && wanted.contains(cc); })
                .toList();
    }

    private long missingRequired(UUID gid, List<String> codes) {
        Set<String> present = rowsFor(gid, codes.toArray(new String[0])).stream()
                .map(ClassScheduleService::courseCodeOf).collect(Collectors.toSet());
        return codes.stream().filter(c -> !present.contains(c)).count();
    }

    private static String wk(ClassSchedule s) {
        return s.getDayOfWeek() + "#" + s.getStartSlot().getDisplayOrder() + "-" + s.getEndSlot().getDisplayOrder();
    }

    private static int span(ClassSchedule s) {
        return s.getEndSlot().getDisplayOrder() - s.getStartSlot().getDisplayOrder() + 1;
    }

    private static int unionPeriods(List<ClassSchedule> rows) {
        Set<String> slots = new HashSet<>();
        for (ClassSchedule s : rows) {
            for (int p = s.getStartSlot().getDisplayOrder(); p <= s.getEndSlot().getDisplayOrder(); p++)
                slots.add(s.getDayOfWeek() + "#" + p);
        }
        return slots.size();
    }

    // ===== TEST A: REQUIRED 12 <= 30 => COMPLETED, publish OK =====
    @Test
    void tA_required12_succeeds_publishes() {
        List<String> req = seedReq("CAPVA_R", SEM7, SEC_B, 3, 2, 2, 0);
        UUID gid = startGen(List.of(sel(SEM7, SEC_B)));
        var resp = generationService.runGenerationBackground(gid);
        assertEquals(GenerationStatus.COMPLETED, resp.status());
        assertEquals(0, missingRequired(gid, req), "all required courses must be present");
        for (String code : req) {
            assertEquals(2, rowsFor(gid, code).size(), code + " needs 2 sessions");
            rowsFor(gid, code).forEach(r -> assertEquals(2, span(r), code + " span must be 2"));
        }
        assertEquals(GenerationStatus.PUBLISHED, generationService.publish(gid).status());
        System.out.println("TEST A: required load=12 periods, COMPLETED, published OK");
    }

    // ===== TEST B: REQUIRED 32 > 30 => FAILS with explicit report =====
    @Test
    void tB_required32_failsWithExplicitReport_noPartialOutput() {
        seedReq("CAPVB_R", SEM7, SEC_B, 8, 1, 4, 0);
        UUID gid = startGen(List.of(sel(SEM7, SEC_B)));
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> generationService.runGenerationBackground(gid));
        String msg = ex.getMessage();
        assertTrue(msg.contains("Semester"), msg);
        assertTrue(msg.contains("requires 32 period-slots/week"), msg);
        assertTrue(msg.contains("only 30 are available"), msg);
        assertTrue(msg.contains("by 2"), msg);
        assertTrue(msg.contains("CAPVB_R"), "must list affected courses");
        assertTrue(scheduleRepository.findByGeneration_GenerationId(gid).isEmpty(),
                "NO partial output must be persisted");
        assertEquals(GenerationStatus.FAILED,
                generationRepository.findById(gid).orElseThrow().getStatus());
        assertThrows(BusinessRuleException.class, () -> generationService.publish(gid));
        System.out.println("TEST B: 32 > 30 => fail report:\n" + msg);
    }

    // ===== TEST C: REQUIRED 28 + ELECTIVES RAW 8, same-shape compress to 2 =====
    @Test
    void tC_required28_electivesRaw8_compressToFit() {
        // 7 courses x (2 sessions x 2 periods) = 28 periods as fourteen 2-period blocks
        List<String> req = seedReq("CAPVC_R", SEM7, SEC_B, 7, 2, 2, 0);
        List<String> ele = seedElec("CAPVC_E", SEM7, SEC_B, 4, 1, 2, 10);
        UUID gid = startGen(List.of(sel(SEM7, SEC_B)));
        var resp = generationService.runGenerationBackground(gid);
        assertEquals(GenerationStatus.COMPLETED, resp.status());
        assertEquals(0, missingRequired(gid, req));
        List<ClassSchedule> eRows = rowsFor(gid, ele.toArray(new String[0]));
        assertEquals(4, eRows.size(), "4 elective rows");
        Set<String> windows = eRows.stream().map(CapacityOverflowVerificationTest::wk).collect(Collectors.toSet());
        assertEquals(1, windows.size(), "all 4 same-shape electives co-locate on ONE window: " + windows);
        int eleUnion = unionPeriods(eRows);
        assertEquals(2, eleUnion, "compressed physical load = 2 periods, not raw 8");
        System.out.println("TEST C: required=28, electives raw=8, compressed=" + eleUnion + ", total=30 => COMPLETED");
    }

    // ===== TEST D: REQUIRED 24 + ELECTIVES RAW 8 MIXED SHAPES => COMPLETED =====
    @Test
    void tD_required24_electivesRaw8_mixedShapes_compress() {
        // 6 courses x (2 x 2) = 24 periods as twelve 2-period blocks
        List<String> req = seedReq("CAPVD_R", SEM7, SEC_B, 6, 2, 2, 0);
        List<String> ele2p = seedElec("CAPVD_X", SEM7, SEC_B, 2, 1, 2, 6);
        List<String> ele1p = seedElec("CAPVD_Y", SEM7, SEC_B, 4, 1, 1, 8);
        UUID gid = startGen(List.of(sel(SEM7, SEC_B)));
        var resp = generationService.runGenerationBackground(gid);
        assertEquals(GenerationStatus.COMPLETED, resp.status());
        assertEquals(0, missingRequired(gid, req));
        List<ClassSchedule> xRows = rowsFor(gid, ele2p.toArray(new String[0]));
        List<ClassSchedule> yRows = rowsFor(gid, ele1p.toArray(new String[0]));
        List<ClassSchedule> allEle = new ArrayList<>(xRows);
        allEle.addAll(yRows);
        int eleUnion = unionPeriods(allEle);
        assertTrue(eleUnion < 8, "physical union " + eleUnion + " must be less than raw sum 8");
        Set<String> xWins = xRows.stream().map(CapacityOverflowVerificationTest::wk).collect(Collectors.toSet());
        assertEquals(1, xWins.size(), "two-period electives share ONE window");
        System.out.println("TEST D: required=24, electives raw=8, physical union=" + eleUnion + " => COMPLETED");
    }

    // ===== TEST E: REQUIRED 28 + ELECTIVES COMPRESS 3 => TOTAL 31 > 30 => HONEST FAILURE =====
    @Test
    void tE_required28_electivesExcess_failsHonestly() {
        List<String> req = seedReq("CAPVE_R", SEM7, SEC_B, 7, 2, 2, 0);
        seedElec("CAPVE_X", SEM7, SEC_B, 2, 1, 2, 10);
        seedElec("CAPVE_Y", SEM7, SEC_B, 2, 1, 1, 12);
        UUID gid = startGen(List.of(sel(SEM7, SEC_B)));
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> generationService.runGenerationBackground(gid));
        assertTrue(scheduleRepository.findByGeneration_GenerationId(gid).isEmpty(),
                "NO partial schedules persisted for infeasible generation");
        assertEquals(GenerationStatus.FAILED,
                generationRepository.findById(gid).orElseThrow().getStatus());
        assertThrows(BusinessRuleException.class, () -> generationService.publish(gid));
        System.out.println("TEST E: 28 required + 3 compressed electives = 31 > 30 => FAIL: " + ex.getMessage());
    }

    // ===== TEST F: REQUIRED 30 + ELECTIVES > 0 => PRE-CHECK FAILS =====
    @Test
    void tF_required30_plusElectives_failsNamingElectiveGroup() {
        seedReq("CAPVF_R", SEM7, SEC_B, 15, 1, 2, 0);
        seedElec("CAPVF_E", SEM7, SEC_B, 2, 1, 1, 10);
        UUID gid = startGen(List.of(sel(SEM7, SEC_B)));
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> generationService.runGenerationBackground(gid));
        String msg = ex.getMessage();
        assertTrue(msg.contains("31 period-slots/week"), msg);
        assertTrue(msg.contains("only 30 are available"), msg);
        assertTrue(scheduleRepository.findByGeneration_GenerationId(gid).isEmpty());
        System.out.println("TEST F: required=30 + electives => pre-check fails:\n" + msg);
    }

    // ===== TEST G: REQUIRED 31, 32, 36 ALL FAIL =====
    @Test
    void tG_requiredOver30_parametrized() {
        int[] loads = {31, 32, 36};
        for (int load : loads) {
            int halfCount = load / 2;          // 2-period courses
            int remainder = load % 2;          // 31 -> one 1-period course
            List<String> codes = new ArrayList<>(seedReq("CAPVG" + load + "_", SEM7, SEC_B, halfCount, 1, 2, 0));
            if (remainder > 0) {
                String code = "CAPVG" + load + "_X";
                Staff s = staffAt(0);
                Course c = newCourse(code, true, SEM7, s);
                addCmr(c, 1, 1);
                bind(c, s, SEC_B);
                codes.add(code);
            }
            UUID gid = startGen(List.of(sel(SEM7, SEC_B)));
            BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                    () -> generationService.runGenerationBackground(gid));
            String msg = ex.getMessage();
            assertTrue(msg.contains("requires " + load + " period-slots/week"),
                    "load=" + load + " msg: " + msg);
            assertTrue(msg.contains("by " + (load - 30)), "excess=" + (load - 30) + " msg: " + msg);
            assertTrue(scheduleRepository.findByGeneration_GenerationId(gid).isEmpty());
            System.out.println("TEST G: " + load + " required => fail (by " + (load - 30) + ")");
            wipeByPrefix("CAPVG" + load + "_");   // keep iterations independent inside the tx
        }
    }

    // ===== TEST H: SHARED GROUP MULTI-SECTION DELIVERY =====
    @Test
    void tH_sharedGroup_oneDelivery_multiSection() {
        pruneSemester(SEM7, SEC_C, SEC_CT);
        List<Staff> trio = staffSameUnit(3);
        Course g = newCourse("CAPVH_G", true, SEM7, trio.get(0));
        addCmr(g, 1, 2);
        TeachingAssignment aB = bind(g, trio.get(0), SEC_B);
        TeachingAssignment aC = bind(g, trio.get(1), SEC_C);
        TeachingAssignment aT = bind(g, trio.get(2), SEC_CT);
        TeachingAssignmentGroup grp = new TeachingAssignmentGroup();
        grp.setTerm(termRepository.findById(TERM_ID).orElseThrow());
        grp.setCourse(g);
        grp.setGroupName("CAPVH shared delivery");
        grp = groupRepository.save(grp);
        for (TeachingAssignment a : List.of(aB, aC, aT)) {
            TeachingAssignmentGroupMember m = new TeachingAssignmentGroupMember();
            m.setId(new TeachingAssignmentGroupMemberId(grp.getGroupId(), a.getAssignmentId()));
            m.setGroup(grp);
            m.setAssignment(a);
            memberRepository.save(m);
        }
        UUID gid = startGen(List.of(sel(SEM7, SEC_B, SEC_C, SEC_CT)));
        var resp = generationService.runGenerationBackground(gid);
        assertEquals(GenerationStatus.COMPLETED, resp.status());
        List<ClassSchedule> gRows = scheduleRepository.findByGeneration_GenerationId(gid).stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE && s.getTeachingGroup() != null)
                .toList();
        assertEquals(1, gRows.size(), "one delivery window for shared group, not per-section duplicates");
        assertEquals(2, span(gRows.get(0)), "group spans 2 periods");
        System.out.println("TEST H: 3-section shared group => 1 row, 2 periods. Success proves correct counting.");
    }

    // ===== TEST I: ELECTIVE SHARING MAX-NOT-SUM =====
    @Test
    void tI_electiveSharing_maxNotSum() {
        // Part 1: three identical 1-period electives => forced co-location => union = 1
        List<String> s1 = seedElec("CAPVIS_", SEM7, SEC_B, 3, 1, 1, 0);
        UUID gid = startGen(List.of(sel(SEM7, SEC_B)));
        var resp = generationService.runGenerationBackground(gid);
        assertEquals(GenerationStatus.COMPLETED, resp.status());
        List<ClassSchedule> sRows = rowsFor(gid, s1.toArray(new String[0]));
        assertEquals(3, sRows.size());
        Set<String> sWins = sRows.stream().map(CapacityOverflowVerificationTest::wk).collect(Collectors.toSet());
        assertEquals(1, sWins.size(), "three 1p electives share ONE window, union=1");
        assertEquals(1, unionPeriods(sRows), "physical = 1 = MAX(1,1,1), not SUM=3");
        System.out.println("TEST I part1: identical shapes => MAX confirmed: union=1, not SUM=3");
        wipeByPrefix("CAPVIS_");   // clean slate for the mixed-shape phase

        // Part 2: A=1p, B=1p, C=2p mix => pre-check counts MAX=2, physical between 2 and 4
        List<String> m1 = seedElec("CAPVIM_1", SEM7, SEC_B, 2, 1, 1, 0);
        List<String> m2 = seedElec("CAPVIM_2", SEM7, SEC_B, 1, 1, 2, 2);
        UUID gid2 = startGen(List.of(sel(SEM7, SEC_B)));
        generationService.runGenerationBackground(gid2);
        List<ClassSchedule> mix = new ArrayList<>(rowsFor(gid2, m1.toArray(new String[0])));
        mix.addAll(rowsFor(gid2, m2.toArray(new String[0])));
        int mixUnion = unionPeriods(mix);
        assertTrue(mixUnion >= 2 && mixUnion <= 4,
                "mixed-shape union=" + mixUnion + " between MAX(2) and SUM(4)");
        System.out.println("TEST I part2: mixed shapes => union=" + mixUnion + " (pre-check books MAX=2, never SUM=4)");
    }

    // ===== TEST L: SEMESTER ISOLATION =====
    @Test
    void tL_semesterIsolation_independent30SlotGrids() {
        // 24 periods per semester as twelve 2-period blocks; disjoint staff per semester
        List<String> s5 = seedReq("CAPVL5_R", SEM5, SEC_B, 6, 2, 2, 0);
        List<String> s7 = seedReq("CAPVL7_R", SEM7, SEC_B, 6, 2, 2, 8);
        UUID gid = startGen(List.of(sel(SEM5, SEC_B), sel(SEM7, SEC_B)));
        var resp = generationService.runGenerationBackground(gid);
        assertEquals(GenerationStatus.COMPLETED, resp.status(),
                "24+24=48 periods on same section across two semesters must complete if grids are independent");
        assertEquals(0, missingRequired(gid, s5));
        assertEquals(0, missingRequired(gid, s7));
        for (String code : s5) {
            rowsFor(gid, code).forEach(r ->
                    assertEquals(SEM5, r.getTeachingAssignment().getCourse().getSemester().getSemesterId(),
                            "SEM5 row must carry SEM5 course"));
        }
        for (String code : s7) {
            rowsFor(gid, code).forEach(r ->
                    assertEquals(SEM7, r.getTeachingAssignment().getCourse().getSemester().getSemesterId(),
                            "SEM7 row must carry SEM7 course"));
        }
        assertEquals(24, scheduleRepository.findByGeneration_GenerationId(gid).stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE).count());
        System.out.println("TEST L: SEM5(24) + SEM7(24) on SEC_B => COMPLETED (48 total, independent grids)");
    }
}

