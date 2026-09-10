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
import com.unicconnect.repository.AcademicTermRepository;
import com.unicconnect.repository.ClassScheduleRepository;
import com.unicconnect.repository.CourseMeetingRequirementRepository;
import com.unicconnect.repository.CourseRepository;
import com.unicconnect.repository.SectionRepository;
import com.unicconnect.repository.SemesterRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LMS / ASSIGNMENT are OPTIONAL post-solver fillers: placed per-section into a
 * slot genuinely free on THAT section's own grid, never displacing courses,
 * never failing generation when they cannot fit, and never letting one
 * section's occupancy block another section.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class LmsAssignmentPlacementTest {

    private static final UUID TERM_ID = UUID.fromString("6eea6860-074e-4e8a-973d-7a538325bef1");
    private static final UUID SEM4 = UUID.fromString("2cbe4a58-543a-43fa-9cf8-c3dc1be5d749");
    private static final UUID SEM7 = UUID.fromString("81e92a47-dce7-4a86-9975-f82fb3d03cfb");
    private static final UUID SEC_A  = UUID.fromString("c183c6d5-9810-40c7-8d93-7636c2c295fe");
    private static final UUID SEC_B  = UUID.fromString("c60d8263-b9c3-4d77-8ae6-b603ca93f044");

    @Autowired TimetableGenerationService generationService;
    @Autowired TeachingAssignmentRepository assignmentRepository;
    @Autowired ClassScheduleRepository scheduleRepository;
    @Autowired CourseRepository courseRepository;
    @Autowired CourseMeetingRequirementRepository requirementRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired SemesterRepository semesterRepository;
    @Autowired AcademicTermRepository termRepository;
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

    // ===== helpers =====

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
        assertTrue(idx >= 0 && idx < pool.size(), "staff pool exhausted at " + idx);
        return pool.get(idx);
    }

    /** Lecturer from the SAME unit as the course (validateLecturerOwnership). */
    private Staff staffForCourse(Course course) {
        UUID unitId = course.getUnit() != null ? course.getUnit().getUnitId() : null;
        return loadStaffPool().stream()
                .filter(s -> s.getUnit() != null && unitId != null
                        && unitId.equals(s.getUnit().getUnitId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no staff in owning unit of " + course.getCourseCode()));
    }

    private void deleteDelivery(String courseCode, UUID semesterId, UUID sectionId) {
        assignmentRepository.findWithDetailsByTermId(TERM_ID).stream()
                .filter(a -> a.getAssignmentStatus() != AssignmentStatus.CANCELLED)
                .filter(a -> a.getCourse().getCourseCode().equals(courseCode))
                .filter(a -> a.getCourse().getSemester() != null
                        && semesterId.equals(a.getCourse().getSemester().getSemesterId()))
                .filter(a -> sectionId.equals(a.getSection().getSectionId()))
                .forEach(a -> {
                    scheduleRepository.findBySectionIdWithDetails(sectionId).stream()
                            .filter(s -> s.getTeachingAssignment() != null
                                    && a.getAssignmentId().equals(
                                            s.getTeachingAssignment().getAssignmentId()))
                            .forEach(scheduleRepository::delete);
                    scheduleRepository.flush();
                    assignmentRepository.delete(a);
                    assignmentRepository.flush();
                });
    }

    private void reshapeToSingleBlock(String courseCode, UUID semesterId,
                                      int sessions, int periods) {
        Course course = courseRepository.findBySemester_SemesterId(semesterId).stream()
                .filter(c -> c.getCourseCode().equals(courseCode)).findFirst().orElseThrow();
        List<CourseMeetingRequirement> cmrs = requirementRepository
                .findAllByCourse_CourseIdIn(List.of(course.getCourseId()));
        CourseMeetingRequirement keep = cmrs.get(0);
        keep.setMeetingType(MeetingType.LECTURE);
        keep.setSessionsPerWeek(sessions);
        keep.setPeriodsPerSession(periods);
        for (int i = 1; i < cmrs.size(); i++) requirementRepository.delete(cmrs.get(i));
        requirementRepository.flush();
    }

    private UUID startGen(UUID semesterId, List<UUID> sectionIds) {
        UUID gid = generationService.create(new CreateGenerationRequest(TERM_ID, null)).generationId();
        generationService.generate(gid, new GenerateTimetableRequest(null,
                List.of(new GenerateTimetableRequest.SemesterSelection(semesterId, sectionIds)),
                false)); // binding off: this suite controls delivery data directly
        return gid;
    }

    private GenerationStatus run(UUID gid) {
        return generationService.runGenerationBackground(gid).status();
    }

    /** All rows (any type) whose coverage includes this section. */
    private List<ClassSchedule> rowsCovering(UUID gid, UUID sectionId) {
        return scheduleRepository.findByGeneration_GenerationId(gid).stream()
                .filter(s -> s.getScheduleType() != ScheduleType.BREAK)
                .filter(s -> {
                    Set<UUID> cov = ClassScheduleService.coveredSections(s);
                    return cov.contains(sectionId);
                }).toList();
    }

    private List<ClassSchedule> specialsFor(List<ClassSchedule> rows) {
        return rows.stream().filter(s -> s.getScheduleType() == ScheduleType.LMS
                || s.getScheduleType() == ScheduleType.ASSIGNMENT).toList();
    }

    /** All generated rows: COURSE rows carry section coverage; LMS/ASSIGNMENT
     * fillers are intentionally section-less (empty coverage) per schema. */
    private List<ClassSchedule> genRows(UUID gid) {
        return scheduleRepository.findByGeneration_GenerationId(gid).stream()
                .filter(s -> s.getScheduleType() != ScheduleType.BREAK)
                .toList();
    }

    private List<ClassSchedule> courseRowsFor(UUID gid, UUID sectionId) {
        return genRows(gid).stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .filter(s -> ClassScheduleService.coveredSections(s).contains(sectionId))
                .toList();
    }

    private boolean coversSlot(ClassSchedule s, int day, int period) {
        return s.getDayOfWeek() != null && s.getDayOfWeek() == day
                && s.getStartSlot().getDisplayOrder() <= period
                && s.getEndSlot().getDisplayOrder() >= period;
    }

    // ===== TEST 1+2: free slots => LMS + ASSIGNMENT created per section =====

    @Test
    void t1_freeSlots_getBothFillers_inOwnFreeSlots() {
        // Light section: keep only CS-2256 + CST-2235 (8 of 30 periods).
        for (String code : List.of("CST-2212", "CST-2213", "CST-2224", "CST-2241", "E-2201")) {
            deleteDelivery(code, SEM4, SEC_A);
        }
        UUID gid = startGen(SEM4, List.of(SEC_A));
        assertEquals(GenerationStatus.COMPLETED, run(gid));

        List<ClassSchedule> allRows = genRows(gid);
        Set<String> codes = allRows.stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .map(s -> s.getTeachingAssignment().getCourse().getCourseCode())
                .collect(Collectors.toSet());
        assertTrue(codes.contains("CS-2256") && codes.contains("CST-2235"),
                "courses must remain complete");

        List<ClassSchedule> fills = specialsFor(allRows);
        // Fill-every-free-slot rule: 30 - 8 course periods = 22 fillers,
        // alternating LMS/ASSIGNMENT so the counts never differ by more than 1.
        assertEquals(22, fills.size(), "every genuinely free slot gets exactly one filler");
        long lmsN = fills.stream().filter(s -> s.getScheduleType() == ScheduleType.LMS).count();
        long asgN = fills.stream().filter(s -> s.getScheduleType() == ScheduleType.ASSIGNMENT).count();
        assertEquals(11, lmsN);
        assertEquals(11, asgN);
        assertTrue(Math.abs(lmsN - asgN) <= 1);
        // Every filler sits in a slot genuinely free of THIS section's courses.
        for (ClassSchedule f : fills) {
            boolean clashes = courseRowsFor(gid, SEC_A).stream()
                    .anyMatch(c -> coversSlot(c, f.getDayOfWeek(),
                            f.getStartSlot().getDisplayOrder()));
            assertFalse(clashes, "filler must occupy a genuinely free slot");
        }
    }

    // ===== TEST 3: fully-packed section omits fillers, generation succeeds =====

    @Test
    void t2_packedSection_omitsFillers_generationStillSucceeds() {
        // SEM7 x Section B has no existing deliveries: build an exactly-full
        // 30-period grid from five SHARED-owner courses (CT-owned courses may
        // not be delivered outside the CT section), one full-day block each.
        List<String> packed = List.of("CST-4112", "CST-4123", "CST-4141", "E-4101", "CST-4137");
        for (String code : packed) {
            Course course = courseRepository.findBySemester_SemesterId(SEM7).stream()
                    .filter(c -> c.getCourseCode().equals(code)).findFirst().orElseThrow();
            requirementRepository.deleteAll(
                    requirementRepository.findByCourse_CourseId(course.getCourseId()));
            CourseMeetingRequirement cmr = new CourseMeetingRequirement();
            cmr.setCourse(course);
            cmr.setMeetingType(MeetingType.LECTURE);
            cmr.setSessionsPerWeek(1);
            cmr.setPeriodsPerSession(6);
            requirementRepository.save(cmr);
            TeachingAssignment ta = assignmentRepository.findWithDetailsByTermId(TERM_ID).stream()
                    .filter(a -> a.getAssignmentStatus() != AssignmentStatus.CANCELLED)
                    .filter(a -> a.getCourse().getCourseId().equals(course.getCourseId()))
                    .filter(a -> SEC_B.equals(a.getSection().getSectionId()))
                    .findFirst()
                    .orElseGet(() -> {
                        TeachingAssignment n = new TeachingAssignment();
                        n.setCourse(course);
                        n.setStaff(staffForCourse(course));
                        n.setSection(sectionRepository.findById(SEC_B).orElseThrow());
                        n.setTerm(termRepository.findById(TERM_ID).orElseThrow());
                        n.setAssignmentStatus(AssignmentStatus.ACTIVE);
                        n.setAssignedAt(Instant.now());
                        return assignmentRepository.save(n);
                    });
            if (!SEC_B.equals(ta.getSection().getSectionId())) {
                throw new IllegalStateException("expected SEC_B delivery for " + code);
            }
        }
        UUID gid = startGen(SEM7, List.of(SEC_B));
        assertEquals(GenerationStatus.COMPLETED, run(gid));

        List<ClassSchedule> rows = genRows(gid);
        long coursePeriods = rows.stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .filter(s -> ClassScheduleService.coveredSections(s).contains(SEC_B))
                .mapToInt(s -> s.getEndSlot().getDisplayOrder()
                        - s.getStartSlot().getDisplayOrder() + 1).sum();
        assertEquals(30, coursePeriods, "section grid is fully packed");
        assertEquals(0, specialsFor(rows).size(),
                "no free slot -> fillers omitted without failing generation");
    }

    // ===== TEST 4+5: cross-section independence =====

    @Test
    void t3_crossSectionIndependence_bFull_aLight() {
        // Section B: exactly 30 periods (five full-day blocks); strip ALL other
        // Sem-4 deliveries from B so nothing else contributes load.
        List<String> bCourses = List.of("CST-2212", "CST-2213", "CST-2224", "CST-2241", "E-2201");
        for (String code : List.of("CS-2256", "CST-2235")) {
            deleteDelivery(code, SEM4, SEC_B);
        }
        for (String code : bCourses) {
            deleteDelivery(code, SEM4, SEC_B);
            reshapeToSingleBlock(code, SEM4, 1, 6);
        }
        for (String code : bCourses) {
            Course course = courseRepository.findBySemester_SemesterId(SEM4).stream()
                    .filter(c -> c.getCourseCode().equals(code)).findFirst().orElseThrow();
            Staff s = staffForCourse(course);
            TeachingAssignment n = new TeachingAssignment();
            n.setCourse(course);
            n.setStaff(s);
            n.setSection(sectionRepository.findById(SEC_B).orElseThrow());
            n.setTerm(termRepository.findById(TERM_ID).orElseThrow());
            n.setAssignmentStatus(AssignmentStatus.ACTIVE);
            n.setAssignedAt(Instant.now());
            assignmentRepository.save(n);
        }
        // Section A: a single 4-period course -> 26 genuinely free slots.
        for (String code : List.of("CST-2212", "CST-2213", "CST-2224",
                "CST-2235", "CST-2241", "E-2201")) {
            deleteDelivery(code, SEM4, SEC_A);
        }

        UUID gid = startGen(SEM4, List.of(SEC_A, SEC_B));
        assertEquals(GenerationStatus.COMPLETED, run(gid));

        List<ClassSchedule> all = genRows(gid);
        List<ClassSchedule> bCoursesRows = all.stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .filter(s -> ClassScheduleService.coveredSections(s).contains(SEC_B))
                .toList();
        assertEquals(30, bCoursesRows.stream()
                .mapToInt(s -> s.getEndSlot().getDisplayOrder()
                        - s.getStartSlot().getDisplayOrder() + 1).sum());

        List<ClassSchedule> fills = specialsFor(all);
        // A grid: 30 - 4 course periods = 26 free slots, all filled (13/13).
        assertEquals(26, fills.size(), "every genuinely free slot gets exactly one filler");
        long lmsN = fills.stream().filter(s -> s.getScheduleType() == ScheduleType.LMS).count();
        long asgN = fills.stream().filter(s -> s.getScheduleType() == ScheduleType.ASSIGNMENT).count();
        assertEquals(13, lmsN);
        assertEquals(13, asgN);
        assertTrue(Math.abs(lmsN - asgN) <= 1);
        List<ClassSchedule> aCourseRows = all.stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .filter(s -> ClassScheduleService.coveredSections(s).contains(SEC_A))
                .toList();
        for (ClassSchedule f : fills) {
            boolean blockedByOwnCourse = aCourseRows.stream()
                    .anyMatch(c -> coversSlot(c, f.getDayOfWeek(),
                            f.getStartSlot().getDisplayOrder()));
            assertFalse(blockedByOwnCourse, "A filler must be free on A's own grid");
            // Independence proof: B's grid is FULL, so whichever period the
            // filler uses is necessarily occupied by a SECTION-B course - the
            // old global scan would have refused it.
            boolean occupiedInB = bCoursesRows.stream()
                    .anyMatch(c -> coversSlot(c, f.getDayOfWeek(),
                            f.getStartSlot().getDisplayOrder()));
            assertTrue(occupiedInB,
                    "A's filler may sit on a period occupied by B - sections are independent");
        }
    }

    // ===== Idempotency: regeneration replaces fillers, never duplicates =====

    @Test
    void t4_regeneration_doesNotDuplicateFillers() {
        for (String code : List.of("CST-2212", "CST-2213", "CST-2224",
                "CST-2241", "E-2201")) {
            deleteDelivery(code, SEM4, SEC_A);
        }
        UUID gid = startGen(SEM4, List.of(SEC_A));
        assertEquals(GenerationStatus.COMPLETED, run(gid));
        int first = specialsFor(genRows(gid)).size();

        generationService.generate(gid, new GenerateTimetableRequest(null,
                List.of(new GenerateTimetableRequest.SemesterSelection(SEM4, List.of(SEC_A))),
                false));
        assertEquals(GenerationStatus.COMPLETED, run(gid));
        int second = specialsFor(genRows(gid)).size();
        assertEquals(first, second, "regeneration must not duplicate fillers");
    }
}
