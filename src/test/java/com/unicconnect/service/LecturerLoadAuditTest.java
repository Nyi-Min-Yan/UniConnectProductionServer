package com.unicconnect.service;

import com.unicconnect.entity.*;
import com.unicconnect.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional(readOnly = true)
public class LecturerLoadAuditTest {

    @Autowired private TeachingAssignmentRepository taRepo;
    @Autowired private AcademicTermRepository termRepo;
    @Autowired private SemesterRepository semRepo;
    @Autowired private CourseMeetingRequirementRepository cmrRepo;
    @Autowired private StaffRepository staffRepo;
    @Autowired private OrganizationalUnitRepository unitRepo;

    @Test
    public void fullAudit() {
        // Find active term
        AcademicTerm activeTerm = termRepo.findAll().stream()
                .filter(t -> t.getStatus() == TermStatus.ACTIVE)
                .findFirst().orElseThrow(() -> new RuntimeException("No active term"));
        System.out.println("============================================");
        System.out.println("ACTIVE TERM: " + activeTerm.getAcademicYear() + " (" + activeTerm.getTermId() + ")");
        System.out.println("============================================");

        // Load all assignments with details
        List<TeachingAssignment> allAssignments = taRepo.findWithDetailsByTermId(activeTerm.getTermId());
        System.out.println("Total assignments: " + allAssignments.size());

        // Get all semesters
        List<Semester> allSemesters = semRepo.findAll();
        Map<UUID, Integer> semNoMap = allSemesters.stream()
                .collect(Collectors.toMap(Semester::getSemesterId, Semester::getSemesterNo));

        // Load all CMRs
        List<CourseMeetingRequirement> allCMRs = cmrRepo.findAll();
        Map<UUID, List<CourseMeetingRequirement>> cmrByCourse = allCMRs.stream()
                .collect(Collectors.groupingBy(cmr -> cmr.getCourse().getCourseId()));

        // Group assignments by semester
        Map<Integer, List<TeachingAssignment>> bySemester = new TreeMap<>();
        for (TeachingAssignment ta : allAssignments) {
            Integer semNo = semNoMap.get(ta.getCourse().getSemester().getSemesterId());
            if (semNo == null) continue;
            bySemester.computeIfAbsent(semNo, k -> new ArrayList<>()).add(ta);
        }

        System.out.println("\n============================================");
        System.out.println("PHASE 1: FULL LECTURER LOAD BY SEMESTER");
        System.out.println("============================================");

        // For each semester, compute lecturer load
        Map<Integer, Map<String, LecturerLoad>> semesterLoads = new TreeMap<>();

        for (Map.Entry<Integer, List<TeachingAssignment>> semEntry : bySemester.entrySet()) {
            int semNo = semEntry.getKey();
            List<TeachingAssignment> semAssignments = semEntry.getValue();

            Map<String, LecturerLoad> lecturerLoads = new LinkedHashMap<>();

            for (TeachingAssignment ta : semAssignments) {
                String staffNo = ta.getStaff().getStaffNo();
                String staffName = ta.getStaff().getStaffName();
                String courseCode = ta.getCourse().getCourseCode();
                String sectionName = ta.getSection().getSectionName();
                String unitName = ta.getStaff().getUnit() != null ? ta.getStaff().getUnit().getUnitName() : "N/A";

                // Calculate CMR weekly periods
                List<CourseMeetingRequirement> cmrs = cmrByCourse.getOrDefault(ta.getCourse().getCourseId(), Collections.emptyList());
                int sessionsPerWeek = 0;
                int periodsPerSession = 0;
                for (CourseMeetingRequirement cmr : cmrs) {
                    sessionsPerWeek += cmr.getSessionsPerWeek();
                    periodsPerSession = Math.max(periodsPerSession, cmr.getPeriodsPerSession());
                }
                int weeklyPeriods = sessionsPerWeek * periodsPerSession;

                LecturerLoad load = lecturerLoads.computeIfAbsent(staffNo,
                        k -> new LecturerLoad(staffNo, staffName, unitName));
                load.addAssignment(courseCode, sectionName, weeklyPeriods);
            }

            semesterLoads.put(semNo, lecturerLoads);

            // Sort by distinct courses DESC, then weekly periods DESC
            List<LecturerLoad> sorted = lecturerLoads.values().stream()
                    .sorted(Comparator.<LecturerLoad>comparingInt(l -> -l.distinctCourses)
                            .thenComparing(Comparator.<LecturerLoad>comparingInt(l -> -l.totalWeeklyPeriods)))
                    .collect(Collectors.toList());

            System.out.println("\n--- Semester " + semNo + " (" + sorted.size() + " lecturers) ---");
            System.out.printf("%-12s %-20s %-30s %-8s %-10s %-10s %-10s%n",
                    "StaffNo", "Name", "Unit", "Courses", "Sections", "Assigns", "WkPeriods");
            System.out.println("-".repeat(110));
            for (LecturerLoad l : sorted) {
                System.out.printf("%-12s %-20s %-30s %-8d %-10d %-10d %-10d%n",
                        l.staffNo, truncate(l.staffName, 20), truncate(l.unitName, 30),
                        l.distinctCourses, l.sections.size(), l.assignmentCount, l.totalWeeklyPeriods);
            }
        }

        // =============================================
        // PHASE 2: 3+ Course lecturers
        // =============================================
        System.out.println("\n============================================");
        System.out.println("PHASE 2: 3+ COURSE LECTURERS");
        System.out.println("============================================");

        for (Map.Entry<Integer, Map<String, LecturerLoad>> semEntry : semesterLoads.entrySet()) {
            int semNo = semEntry.getKey();
            List<LecturerLoad> overloaded = semEntry.getValue().values().stream()
                    .filter(l -> l.distinctCourses >= 3)
                    .sorted(Comparator.<LecturerLoad>comparingInt(l -> -l.distinctCourses)
                            .thenComparing(Comparator.<LecturerLoad>comparingInt(l -> -l.totalWeeklyPeriods)))
                    .collect(Collectors.toList());

            if (overloaded.isEmpty()) {
                System.out.println("\nSemester " + semNo + ": No 3+ course lecturers");
                continue;
            }

            System.out.println("\n=== Semester " + semNo + " ===");
            for (LecturerLoad l : overloaded) {
                System.out.println("\nStaff ID: " + l.staffNo + " | " + l.staffName + " | Unit: " + l.unitName);
                System.out.println("  DISTINCT COURSES: " + l.distinctCourses);
                System.out.println("  SECTIONS: " + l.sections.size());
                System.out.println("  WEEKLY PERIODS: " + l.totalWeeklyPeriods);
                System.out.println("  Course Details:");
                for (Map.Entry<String, CourseDetail> ce : l.courseDetails.entrySet()) {
                    CourseDetail cd = ce.getValue();
                    System.out.println("    " + ce.getKey() + ": sections=" + String.join(",", cd.sections)
                            + " | weeklyPeriods=" + cd.weeklyPeriods);
                }
            }
        }

        // =============================================
        // PHASE 3: Cross-semester lecturer audit (Mid Term)
        // =============================================
        System.out.println("\n============================================");
        System.out.println("PHASE 3: CROSS-SEMESTER LECTURER AUDIT (Mid Term: 1,3,5,7)");
        System.out.println("============================================");

        Set<Integer> midTermSemesters = Set.of(1, 3, 5, 7);
        // Collect all lecturers in mid-term semesters
        Map<String, Map<Integer, List<String>>> crossSemester = new TreeMap<>(); // staffNo -> semNo -> courses
        for (Map.Entry<Integer, Map<String, LecturerLoad>> semEntry : semesterLoads.entrySet()) {
            int semNo = semEntry.getKey();
            if (!midTermSemesters.contains(semNo)) continue;
            for (Map.Entry<String, LecturerLoad> le : semEntry.getValue().entrySet()) {
                String staffNo = le.getKey();
                LecturerLoad load = le.getValue();
                crossSemester.computeIfAbsent(staffNo, k -> new LinkedHashMap<>())
                        .put(semNo, new ArrayList<>(load.courseDetails.keySet()));
            }
        }

        // Find lecturers in 2+ mid-term semesters
        List<String> multiSemLecturers = crossSemester.entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        System.out.printf("%-12s %-20s %-8s %-8s %-8s %-8s %-10s%n",
                "StaffNo", "Name", "Sem1", "Sem3", "Sem5", "Sem7", "Total");
        System.out.println("-".repeat(80));

        for (String staffNo : multiSemLecturers) {
            Map<Integer, List<String>> sems = crossSemester.get(staffNo);
            String name = "";
            // find name from any load
            for (Map<String, LecturerLoad> sl : semesterLoads.values()) {
                if (sl.containsKey(staffNo)) { name = sl.get(staffNo).staffName; break; }
            }
            int totalCourses = sems.values().stream().mapToInt(List::size).sum();
            String s1 = sems.containsKey(1) ? String.join(",", sems.get(1)) : "-";
            String s3 = sems.containsKey(3) ? String.join(",", sems.get(3)) : "-";
            String s5 = sems.containsKey(5) ? String.join(",", sems.get(5)) : "-";
            String s7 = sems.containsKey(7) ? String.join(",", sems.get(7)) : "-";
            System.out.printf("%-12s %-20s %-8s %-8s %-8s %-8s %-10d%n",
                    staffNo, truncate(name, 20), s1, s3, s5, s7, totalCourses);
        }

        // Highlight bottleneck lecturers
        System.out.println("\n--- BOTTLENECK LECTURERS (appear in Sem5 + other Mid Term semesters) ---");
        for (String staffNo : multiSemLecturers) {
            Map<Integer, List<String>> sems = crossSemester.get(staffNo);
            if (sems.containsKey(5) && sems.keySet().size() >= 2) {
                String name = "";
                for (Map<String, LecturerLoad> sl : semesterLoads.values()) {
                    if (sl.containsKey(staffNo)) { name = sl.get(staffNo).staffName; break; }
                }
                System.out.println(staffNo + " (" + name + "): " + sems);
            }
        }

        // =============================================
        // PHASE 7: Department-level lecturer inventory
        // =============================================
        System.out.println("\n============================================");
        System.out.println("PHASE 7: DEPARTMENT LECTURER INVENTORY");
        System.out.println("============================================");

        // For each unit that has overloaded lecturers, list ALL lecturers and their loads
        Set<String> overloadedUnits = new HashSet<>();
        for (Map<String, LecturerLoad> sl : semesterLoads.values()) {
            for (LecturerLoad l : sl.values()) {
                if (l.distinctCourses >= 3) overloadedUnits.add(l.unitName);
            }
        }

        for (String unitName : overloadedUnits.stream().sorted().collect(Collectors.toList())) {
            System.out.println("\n--- Unit: " + unitName + " ---");
            System.out.printf("%-12s %-20s", "StaffNo", "Name");
            for (int i = 1; i <= 7; i++) System.out.printf(" %-15s", "Sem" + i);
            System.out.println();
            System.out.println("-".repeat(130));

            // Find all staff in this unit
            Set<String> unitStaffNos = new LinkedHashSet<>();
            for (Map<String, LecturerLoad> sl : semesterLoads.values()) {
                for (LecturerLoad l : sl.values()) {
                    if (l.unitName.equals(unitName)) unitStaffNos.add(l.staffNo);
                }
            }
            // Also check staff repo
            for (Staff st : staffRepo.findAll()) {
                if (st.getUnit() != null && st.getUnit().getUnitName().equals(unitName)) {
                    unitStaffNos.add(st.getStaffNo());
                }
            }

            for (String staffNo : unitStaffNos.stream().sorted().collect(Collectors.toList())) {
                String name = "";
                for (Map<String, LecturerLoad> sl : semesterLoads.values()) {
                    if (sl.containsKey(staffNo)) { name = sl.get(staffNo).staffName; break; }
                }
                if (name.isEmpty()) {
                    // get from staff repo
                    for (Staff st : staffRepo.findAll()) {
                        if (st.getStaffNo().equals(staffNo)) { name = st.getStaffName(); break; }
                    }
                }
                System.out.printf("%-12s %-20s", staffNo, truncate(name, 20));
                for (int i = 1; i <= 7; i++) {
                    Map<String, LecturerLoad> sl = semesterLoads.get(i);
                    if (sl != null && sl.containsKey(staffNo)) {
                        LecturerLoad l = sl.get(staffNo);
                        System.out.printf(" %-15s", l.distinctCourses + "c/" + l.totalWeeklyPeriods + "p");
                    } else {
                        System.out.printf(" %-15s", "-");
                    }
                }
                System.out.println();
            }
        }

        // =============================================
        // SUMMARY STATISTICS
        // =============================================
        System.out.println("\n============================================");
        System.out.println("SUMMARY: LECTURER LOAD DISTRIBUTION");
        System.out.println("============================================");

        for (Map.Entry<Integer, Map<String, LecturerLoad>> semEntry : semesterLoads.entrySet()) {
            int semNo = semEntry.getKey();
            Map<String, LecturerLoad> loads = semEntry.getValue();
            long c1 = loads.values().stream().filter(l -> l.distinctCourses == 1).count();
            long c2 = loads.values().stream().filter(l -> l.distinctCourses == 2).count();
            long c3 = loads.values().stream().filter(l -> l.distinctCourses == 3).count();
            long c4 = loads.values().stream().filter(l -> l.distinctCourses >= 4).count();
            System.out.printf("Sem%2d: 1c=%d  2c=%d  3c=%d  4+c=%d  total=%d%n",
                    semNo, c1, c2, c3, c4, loads.size());
        }
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max - 1) + ".." : s;
    }

    // === Data holders ===

    static class LecturerLoad {
        String staffNo, staffName, unitName;
        int distinctCourses, assignmentCount, totalWeeklyPeriods;
        Set<String> sections = new LinkedHashSet<>();
        Map<String, CourseDetail> courseDetails = new LinkedHashMap<>();

        LecturerLoad(String staffNo, String staffName, String unitName) {
            this.staffNo = staffNo;
            this.staffName = staffName;
            this.unitName = unitName;
        }

        void addAssignment(String courseCode, String sectionName, int weeklyPeriods) {
            sections.add(sectionName);
            assignmentCount++;
            totalWeeklyPeriods += weeklyPeriods;
            CourseDetail cd = courseDetails.computeIfAbsent(courseCode, k -> new CourseDetail());
            cd.sections.add(sectionName);
            cd.weeklyPeriods += weeklyPeriods;
            distinctCourses = courseDetails.size();
        }
    }

    static class CourseDetail {
        Set<String> sections = new LinkedHashSet<>();
        int weeklyPeriods;
    }
}
