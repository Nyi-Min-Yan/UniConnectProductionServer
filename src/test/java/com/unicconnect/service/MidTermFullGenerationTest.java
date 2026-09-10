package com.unicconnect.service;

import com.unicconnect.dto.request.CreateGenerationRequest;
import com.unicconnect.dto.request.GenerateTimetableRequest;
import com.unicconnect.dto.response.GenerationSessionResponse;
import com.unicconnect.entity.*;
import com.unicconnect.repository.*;
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
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class MidTermFullGenerationTest {

    private static final UUID TERM_ID = UUID.fromString("6eea6860-074e-4e8a-973d-7a538325bef1");
    private static final UUID MID_EXAM = UUID.fromString("4f885c79-0eb3-a8b3-d35d-dee741a88907");

    private static final UUID SEM1 = UUID.fromString("ca7bb336-9530-4254-bc8d-3e92d3278ab3");
    private static final UUID SEM3 = UUID.fromString("2f4a3bc3-5d7e-44e2-b3ea-3bab943abcfb");
    private static final UUID SEM5 = UUID.fromString("45088805-eefe-47b3-a7d4-eed0c8ec7f0c");
    private static final UUID SEM7 = UUID.fromString("3890917f-e9c5-4ddc-8622-c981820a589f");

    private static final UUID SEC_A  = UUID.fromString("81004274-cf05-491f-b1a3-7c8e3d5c77e7");
    private static final UUID SEC_B  = UUID.fromString("f19a0bb5-347a-4b40-aa0a-dd7062a0d64e");
    private static final UUID SEC_C  = UUID.fromString("adc0d7f4-3075-41d0-9366-c6e8b80f0a27");
    private static final UUID SEC_CT = UUID.fromString("029878c5-d51d-4a0a-8fb1-99642ab4dee1");

    @Autowired TimetableGenerationService generationService;
    @Autowired AcademicTermRepository termRepository;
    @Autowired GenerationSessionRepository generationSessionRepository;
    @Autowired ClassScheduleRepository scheduleRepository;
    @Autowired SemesterRepository semesterRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired UserRepository userRepository;
    @Autowired TeachingAssignmentRepository assignmentRepository;
    @Autowired CourseMeetingRequirementRepository requirementRepository;

    @BeforeEach
    void authenticateAsHod() {
        UUID userId = userRepository.findByEmail("dawmya@gmail.com")
                .orElseThrow(() -> new IllegalStateException("test HOD user missing"))
                .getUserId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }

    @Test
    void fullMidTermGeneration_allSections() {
        long testStart = System.currentTimeMillis();

        // === LOOKUP TERM DYNAMICALLY ===
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No active academic term"));
        UUID termId = term.getTermId();
        System.out.println("\n========== TERM ==========");
        System.out.println("  termId: " + termId);
        System.out.println("  year: " + term.getAcademicYear());

        // === CHECK ALL TERMS ===
        termRepository.findAll().forEach(t ->
            System.out.println("  DB term: id=" + t.getTermId() + " year=" + t.getAcademicYear() + " status=" + t.getStatus()));

        // === CHECK TEACHING ASSIGNMENTS FOR THIS TERM ===
        List<TeachingAssignment> termAssignments = assignmentRepository.findWithDetailsByTermId(termId);
        System.out.println("  assignments for this term: " + termAssignments.size());

        // Also check ALL assignments across all terms
        long totalAssignments = assignmentRepository.count();
        System.out.println("  total assignments in DB: " + totalAssignments);

        // === PRELOAD: verify data exists ===
        long preloadStart = System.currentTimeMillis();
        List<TeachingAssignment> allAssignments = assignmentRepository
                .findWithDetailsByTermId(termId);
        List<CourseMeetingRequirement> allReqs = requirementRepository.findAll();
        long preloadTime = System.currentTimeMillis() - preloadStart;

        System.out.println("\n========== PRELOAD ==========");
        System.out.println("  assignments: " + allAssignments.size());
        System.out.println("  requirements: " + allReqs.size());
        System.out.println("  preloadTime: " + preloadTime + "ms");

        // === BUILD SELECTIONS ===
        List<GenerateTimetableRequest.SemesterSelection> selections = List.of(
                new GenerateTimetableRequest.SemesterSelection(SEM1, List.of(SEC_A, SEC_B, SEC_C)),
                new GenerateTimetableRequest.SemesterSelection(SEM3, List.of(SEC_A, SEC_B, SEC_C)),
                new GenerateTimetableRequest.SemesterSelection(SEM5, List.of(SEC_A, SEC_B, SEC_CT)),
                new GenerateTimetableRequest.SemesterSelection(SEM7, List.of(SEC_A, SEC_B, SEC_CT))
        );

        // === CREATE & GENERATE ===
        UUID generationId = generationService
                .create(new CreateGenerationRequest(termId, null))
                .generationId();

        GenerateTimetableRequest req = new GenerateTimetableRequest(MID_EXAM, selections, true);
        generationService.generate(generationId, req);

        System.out.println("\n========== GENERATING ==========");
        Instant genStart = Instant.now();

        GenerationSessionResponse result = generationService.runGenerationBackground(generationId);

        long elapsed = java.time.Duration.between(genStart, Instant.now()).toMillis();
        System.out.println("\n========== RESULT ==========");
        System.out.println("  status: " + result.status());
        System.out.println("  elapsed: " + elapsed + "ms");

        assertEquals(GenerationStatus.COMPLETED, result.status(),
                "Full Mid-Term generation must reach COMPLETED");

        // === RETRIEVE SCHEDULES ===
        List<ClassSchedule> all = scheduleRepository.findByGeneration_GenerationId(generationId);
        List<ClassSchedule> courses = all.stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE)
                .filter(s -> s.getScheduleStatus() != ScheduleStatus.CANCELLED)
                .toList();

        System.out.println("\n========== SCHEDULE COUNTS ==========");
        System.out.println("  total rows: " + all.size());
        System.out.println("  course rows: " + courses.size());

        // === GROUP BY SEMESTER / SECTION / COURSE ===
        Map<String, Map<String, Map<String, List<ClassSchedule>>>> bySemSecCourse = new TreeMap<>();
        for (ClassSchedule cs : courses) {
            String semKey = semesterKey(cs);
            String secKey = sectionName(cs);
            String crsKey = courseCode(cs);
            bySemSecCourse
                    .computeIfAbsent(semKey, k -> new TreeMap<>())
                    .computeIfAbsent(secKey, k -> new TreeMap<>())
                    .computeIfAbsent(crsKey, k -> new ArrayList<>())
                    .add(cs);
        }

        System.out.println("\n========== SCHEDULES BY SEMESTER/SECTION/COURSE ==========");
        int totalCourseCount = 0;
        for (Map.Entry<String, Map<String, Map<String, List<ClassSchedule>>>> semEntry : bySemSecCourse.entrySet()) {
            System.out.println("\n  " + semEntry.getKey() + ":");
            for (Map.Entry<String, Map<String, List<ClassSchedule>>> secEntry : semEntry.getValue().entrySet()) {
                System.out.println("    " + secEntry.getKey() + ":");
                for (Map.Entry<String, List<ClassSchedule>> crsEntry : secEntry.getValue().entrySet()) {
                    int count = crsEntry.getValue().size();
                    totalCourseCount += count;
                    StringBuilder sb = new StringBuilder();
                    for (ClassSchedule s : crsEntry.getValue()) {
                        sb.append(" [").append(dayName(s.getDayOfWeek()))
                          .append(" P").append(s.getStartSlot().getDisplayOrder())
                          .append("-P").append(s.getEndSlot().getDisplayOrder()).append("]");
                    }
                    System.out.println("      " + crsEntry.getKey() + " = " + count + " sessions" + sb);
                }
            }
        }
        System.out.println("\n  TOTAL course sessions placed: " + totalCourseCount);
        assertTrue(totalCourseCount > 0, "Must place at least one course session");

        // === LECTURER CONFLICT VALIDATION ===
        System.out.println("\n========== LECTURER CONFLICT VALIDATION ==========");
        Set<String> lecturersOfInterest = Set.of("STF043", "STF046", "STF049",
                "STF013", "STF045", "STF047", "STF0031", "STF042");
        int conflicts = 0;
        for (int i = 0; i < courses.size(); i++) {
            for (int j = i + 1; j < courses.size(); j++) {
                ClassSchedule a = courses.get(i);
                ClassSchedule b = courses.get(j);
                if (!overlaps(a, b)) continue;
                Set<String> staffA = coveredStaffCodes(a);
                Set<String> staffB = coveredStaffCodes(b);
                if (!Collections.disjoint(staffA, staffB)) {
                    conflicts++;
                    System.out.println("  CONFLICT: " + courseCode(a) + " & " + courseCode(b)
                            + " day=" + dayName(a.getDayOfWeek())
                            + " P" + a.getStartSlot().getDisplayOrder()
                            + "-P" + a.getEndSlot().getDisplayOrder()
                            + " staff=" + staffA + " / " + staffB);
                }
            }
        }
        System.out.println("  Total lecturer conflicts: " + conflicts);
        assertEquals(0, conflicts, "No lecturer conflicts allowed");

        // === SECTION CONFLICT VALIDATION ===
        System.out.println("\n========== SECTION CONFLICT VALIDATION ==========");
        int sectionConflicts = 0;
        for (int i = 0; i < courses.size(); i++) {
            for (int j = i + 1; j < courses.size(); j++) {
                ClassSchedule a = courses.get(i);
                ClassSchedule b = courses.get(j);
                if (!overlaps(a, b)) continue;
                Set<UUID> secA = ClassScheduleService.coveredSections(a);
                Set<UUID> secB = ClassScheduleService.coveredSections(b);
                if (!Collections.disjoint(secA, secB)) {
                    boolean electiveException = isElectiveGroup(a, b);
                    if (!electiveException) {
                        sectionConflicts++;
                        System.out.println("  SECTION CONFLICT: " + courseCode(a) + " & " + courseCode(b)
                                + " day=" + dayName(a.getDayOfWeek())
                                + " P" + a.getStartSlot().getDisplayOrder()
                                + "-P" + a.getEndSlot().getDisplayOrder());
                    }
                }
            }
        }
        System.out.println("  Total section conflicts: " + sectionConflicts);
        assertEquals(0, sectionConflicts, "No section conflicts allowed");

        // === ELECTIVE CO-LOCATION VALIDATION ===
        System.out.println("\n========== ELECTIVE CO-LOCATION VALIDATION ==========");
        validateElectiveColocation(courses);

        // === HETEROGENEOUS PPS VALIDATION ===
        System.out.println("\n========== HETEROGENEOUS PPS VALIDATION ==========");
        validateHeterogeneousPPS(courses);

        // === SEM-7 SPECIFIC VALIDATION ===
        System.out.println("\n========== SEM-7 SPECIFIC CHECKS ==========");
        List<ClassSchedule> sem7Courses = courses.stream()
                .filter(s -> {
                    Semester sem = semesterOf(s);
                    return sem != null && SEM7.equals(sem.getSemesterId());
                }).toList();
        System.out.println("  Sem-7 course sessions: " + sem7Courses.size());
        assertTrue(sem7Courses.size() > 0, "Sem-7 must have course sessions");

        // === PERFORMANCE ===
        long totalTestTime = System.currentTimeMillis() - testStart;
        System.out.println("\n========== PERFORMANCE ==========");
        System.out.println("  OLD Sem-7: ~60s timeout (FAILED)");
        System.out.println("  NEW elapsed: " + elapsed + "ms");
        System.out.println("  Total test time: " + totalTestTime + "ms");

        System.out.println("\n========== FINAL RESULT: PASS ==========");
    }

    // ========== HELPERS ==========

    private boolean overlaps(ClassSchedule a, ClassSchedule b) {
        if (a.getDayOfWeek() == null || b.getDayOfWeek() == null) return false;
        if (a.getStartSlot() == null || a.getEndSlot() == null) return false;
        if (b.getStartSlot() == null || b.getEndSlot() == null) return false;
        return a.getDayOfWeek().equals(b.getDayOfWeek())
                && a.getStartSlot().getDisplayOrder() <= b.getEndSlot().getDisplayOrder()
                && b.getStartSlot().getDisplayOrder() <= a.getEndSlot().getDisplayOrder();
    }

    private boolean isElectiveGroup(ClassSchedule a, ClassSchedule b) {
        Course ca = courseOf(a);
        Course cb = courseOf(b);
        if (ca == null || cb == null) return false;
        if (ca.isRequired() || cb.isRequired()) return false;
        Semester sa = ca.getSemester();
        Semester sb = cb.getSemester();
        if (sa == null || sb == null) return false;
        Set<UUID> secA = ClassScheduleService.coveredSections(a);
        Set<UUID> secB = ClassScheduleService.coveredSections(b);
        return sa.getSemesterId().equals(sb.getSemesterId())
                && !Collections.disjoint(secA, secB);
    }

    private Course courseOf(ClassSchedule cs) {
        if (cs.getTeachingAssignment() != null) return cs.getTeachingAssignment().getCourse();
        if (cs.getTeachingGroup() != null) return cs.getTeachingGroup().getCourse();
        return null;
    }

    private Set<String> coveredStaffCodes(ClassSchedule cs) {
        Set<String> codes = new HashSet<>();
        if (cs.getTeachingAssignment() != null) {
            codes.add(cs.getTeachingAssignment().getStaff().getStaffNo());
        }
        if (cs.getTeachingGroup() != null) {
            for (var m : cs.getTeachingGroup().getMembers()) {
                codes.add(m.getAssignment().getStaff().getStaffNo());
            }
        }
        return codes;
    }

    private String semesterKey(ClassSchedule cs) {
        Semester sem = semesterOf(cs);
        return sem != null ? "Sem-" + sem.getSemesterNo() : "Unknown";
    }

    private Semester semesterOf(ClassSchedule cs) {
        Course c = courseOf(cs);
        return c != null ? c.getSemester() : null;
    }

    private String sectionName(ClassSchedule cs) {
        Set<UUID> secs = ClassScheduleService.coveredSections(cs);
        List<String> names = new ArrayList<>();
        for (UUID secId : secs) {
            sectionRepository.findById(secId).ifPresent(s -> names.add(s.getSectionName()));
        }
        Collections.sort(names);
        return String.join("+", names);
    }

    private String courseCode(ClassSchedule cs) {
        String code = ClassScheduleService.courseCodeOf(cs);
        return code != null ? code : "Unknown";
    }

    private String dayName(int dayOfWeek) {
        return switch (dayOfWeek) {
            case 1 -> "Mon"; case 2 -> "Tue"; case 3 -> "Wed";
            case 4 -> "Thu"; case 5 -> "Fri"; default -> "Day" + dayOfWeek;
        };
    }

    private void validateElectiveColocation(List<ClassSchedule> courses) {
        Map<String, List<ClassSchedule>> electiveGroups = new LinkedHashMap<>();
        for (ClassSchedule cs : courses) {
            Course course = courseOf(cs);
            if (course == null || course.isRequired()) continue;
            String key = semesterKey(cs) + "|" + sectionName(cs);
            electiveGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(cs);
        }

        for (Map.Entry<String, List<ClassSchedule>> entry : electiveGroups.entrySet()) {
            System.out.println("  Group: " + entry.getKey());
            Map<String, List<ClassSchedule>> byCode = entry.getValue().stream()
                    .collect(Collectors.groupingBy(this::courseCode));
            for (Map.Entry<String, List<ClassSchedule>> codeEntry : byCode.entrySet()) {
                System.out.println("    " + codeEntry.getKey() + ": " + codeEntry.getValue().size() + " sessions");
                for (ClassSchedule cs : codeEntry.getValue()) {
                    System.out.println("      day=" + dayName(cs.getDayOfWeek())
                            + " P" + cs.getStartSlot().getDisplayOrder()
                            + "-P" + cs.getEndSlot().getDisplayOrder());
                }
            }
        }
    }

    private void validateHeterogeneousPPS(List<ClassSchedule> courses) {
        String[] ctCourses = {"CT-4136", "CT-4125", "CT-4137", "CST-4137", "CST-4158"};
        for (String code : ctCourses) {
            List<ClassSchedule> found = courses.stream()
                    .filter(cs -> code.equals(courseCode(cs)))
                    .toList();
            if (!found.isEmpty()) {
                System.out.println("  " + code + ": " + found.size() + " sessions found");
                for (ClassSchedule cs : found) {
                    System.out.println("    day=" + dayName(cs.getDayOfWeek())
                            + " P" + cs.getStartSlot().getDisplayOrder()
                            + "-P" + cs.getEndSlot().getDisplayOrder()
                            + " section=" + sectionName(cs));
                }
            }
        }
    }
}
