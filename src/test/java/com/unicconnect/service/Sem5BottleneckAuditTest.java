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
public class Sem5BottleneckAuditTest {

    @Autowired private TeachingAssignmentRepository taRepo;
    @Autowired private AcademicTermRepository termRepo;
    @Autowired private SemesterRepository semRepo;
    @Autowired private CourseMeetingRequirementRepository cmrRepo;

    @Test
    public void auditSem5CrossSemester() {
        AcademicTerm activeTerm = termRepo.findAll().stream()
                .filter(t -> t.getStatus() == TermStatus.ACTIVE)
                .findFirst().orElseThrow(() -> new RuntimeException("No active term"));
        List<TeachingAssignment> allAssignments = taRepo.findWithDetailsByTermId(activeTerm.getTermId());
        Map<UUID, Integer> semNoMap = semRepo.findAll().stream()
                .collect(Collectors.toMap(Semester::getSemesterId, Semester::getSemesterNo));

        // CMRs: courseId -> total sessions/week and max periods/session
        Map<UUID, List<CourseMeetingRequirement>> cmrByCourse = cmrRepo.findAll().stream()
                .collect(Collectors.groupingBy(cmr -> cmr.getCourse().getCourseId()));

        // For each lecturer, map: semNo -> list of (courseCode, section)
        Map<String, Map<Integer, List<String>>> staffSemCourses = new TreeMap<>();
        // staffNo -> staffName
        Map<String, String> staffNames = new TreeMap<>();
        // staffNo -> unitName
        Map<String, String> staffUnits = new TreeMap<>();

        for (TeachingAssignment ta : allAssignments) {
            Integer semNo = semNoMap.get(ta.getCourse().getSemester().getSemesterId());
            if (semNo == null) continue;
            String staffNo = ta.getStaff().getStaffNo();
            staffNames.putIfAbsent(staffNo, ta.getStaff().getStaffName());
            staffUnits.putIfAbsent(staffNo, ta.getStaff().getUnit() != null ? ta.getStaff().getUnit().getUnitName() : "N/A");
            String courseSec = ta.getCourse().getCourseCode() + "[" + ta.getSection().getSectionName() + "]";
            staffSemCourses.computeIfAbsent(staffNo, k -> new TreeMap<>())
                    .computeIfAbsent(semNo, k -> new ArrayList<>())
                    .add(courseSec);
        }

        System.out.println("===== SEM5 BOTTLENECK ANALYSIS: CROSS-SEMESTER LECTURERS =====");
        Set<Integer> midTerm = Set.of(1,3,5,7);
        // For every lecturer in Sem5, determine which OTHER mid-term semesters they appear in
        for (String staffNo : staffSemCourses.keySet()) {
            Map<Integer, List<String>> sems = staffSemCourses.get(staffNo);
            if (!sems.containsKey(5)) continue;
            List<Integer> otherMid = sems.keySet().stream()
                    .filter(midTerm::contains).filter(s -> s != 5).collect(Collectors.toList());
            if (!otherMid.isEmpty()) {
                System.out.println("\n*** " + staffNo + " (" + staffNames.get(staffNo) + ") - " + staffUnits.get(staffNo));
                System.out.println("    MID-TERM PRESENCE:");
                for (Integer sem : new Integer[]{1,3,7}) {
                    if (sems.containsKey(sem)) {
                        System.out.println("      Sem" + sem + ": " + String.join(", ", sems.get(sem)));
                    }
                }
                System.out.println("    SEM5: " + String.join(", ", sems.get(5)));
                System.out.println("    -> THIS LECTURER'S SEM5 CANDIDATE WINDOWS REDUCED BY PRIOR MID-TERM SEMESTERS");
            }
        }

        System.out.println("\n\n===== SEM5 FULL LECTURER DETAIL (all Sem5 units) =====");
        System.out.println("(Cross-semester flagged with ***, 3-course flagged with ###)");
        for (String staffNo : staffSemCourses.keySet()) {
            Map<Integer, List<String>> sems = staffSemCourses.get(staffNo);
            if (!sems.containsKey(5)) continue;
            List<Integer> otherMid = sems.keySet().stream()
                    .filter(midTerm::contains).filter(s -> s != 5).collect(Collectors.toList());
            int distinctCourses = sems.get(5).stream()
                    .map(s -> s.substring(0, s.indexOf('[')))
                    .collect(Collectors.toSet()).size();
            boolean cross = !otherMid.isEmpty();
            boolean three = distinctCourses >= 3;
            String flag = (cross ? "*** " : "") + (three ? "### " : "");
            System.out.println("\n" + flag + staffNo + " (" + staffNames.get(staffNo) + ") - " + staffUnits.get(staffNo));
            System.out.println("    Sem5 distinct courses = " + distinctCourses + ", cross-semester = " + cross);
            System.out.println("    Sem5: " + String.join(", ", sems.get(5)));
        }

        // Compute Sem5 weekly period demand per unit/course
        System.out.println("\n\n===== SEM5 COURSES AND CMR / WEEKLY PERIODS =====");
        Set<String> seenCourses = new HashSet<>();
        for (TeachingAssignment ta : allAssignments) {
            Integer semNo = semNoMap.get(ta.getCourse().getSemester().getSemesterId());
            if (semNo == null || semNo != 5) continue;
            String cc = ta.getCourse().getCourseCode();
            if (!seenCourses.contains(cc)) {
                seenCourses.add(cc);
                List<CourseMeetingRequirement> cmrs = cmrByCourse.getOrDefault(ta.getCourse().getCourseId(), Collections.emptyList());
                StringBuilder sb = new StringBuilder();
                int totalPeriods = 0;
                for (CourseMeetingRequirement cmr : cmrs) {
                    int wp = cmr.getSessionsPerWeek() * cmr.getPeriodsPerSession();
                    totalPeriods += wp;
                    sb.append(cmr.getMeetingType()).append(":").append(cmr.getSessionsPerWeek())
                      .append("x").append(cmr.getPeriodsPerSession()).append("p").append(" ");
                }
                System.out.println("  " + cc + ": " + sb.toString().trim() + " | totalPeriods=" + totalPeriods
                        + " | semester=" + ta.getCourse().getSemester().getSemesterNo());
            }
        }
    }
}
