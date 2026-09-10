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
public class ReassignmentDetailAuditTest {

    @Autowired private TeachingAssignmentRepository taRepo;
    @Autowired private AcademicTermRepository termRepo;
    @Autowired private SemesterRepository semRepo;
    @Autowired private TeachingAssignmentGroupMemberRepository groupMemberRepo;
    @Autowired private TeachingAssignmentGroupRepository groupRepo;

    @Test
    public void exactAssignmentsForChange() {
        AcademicTerm activeTerm = termRepo.findAll().stream()
                .filter(t -> t.getStatus() == TermStatus.ACTIVE)
                .findFirst().orElseThrow(() -> new RuntimeException("No active term"));
        List<TeachingAssignment> allAssignments = taRepo.findWithDetailsByTermId(activeTerm.getTermId());
        Map<UUID, Integer> semNoMap = semRepo.findAll().stream()
                .collect(Collectors.toMap(Semester::getSemesterId, Semester::getSemesterNo));

        // Identify targets: all assignments for STF0034 and STF014 in Sem5
        System.out.println("===== TARGET ASSIGNMENTS TO RECONSIDER (STF014, STF0034 in Sem5) =====");
        for (TeachingAssignment ta : allAssignments) {
            Integer semNo = semNoMap.get(ta.getCourse().getSemester().getSemesterId());
            if (semNo == null || semNo != 5) continue;
            String staffNo = ta.getStaff().getStaffNo();
            if (staffNo.equals("STF014") || staffNo.equals("STF0034")) {
                System.out.println("  assignmentId=" + ta.getAssignmentId()
                        + " | staff=" + ta.getStaff().getStaffNo() + "(" + ta.getStaff().getStaffName() + ")"
                        + " | course=" + ta.getCourse().getCourseCode()
                        + " | section=" + ta.getSection().getSectionName()
                        + " | term=" + ta.getTerm().getAcademicYear());
            }
        }

        // Check combined-group membership for ALL Sem5 courses
        System.out.println("\n===== SEM5 COMBINED GROUP MEMBERSHIP CHECK =====");
        Map<UUID, Integer> semNoByCourse = new HashMap<>();
        for (TeachingAssignment ta : allAssignments) {
            semNoByCourse.put(ta.getCourse().getCourseId(), semNoMap.get(ta.getCourse().getSemester().getSemesterId()));
        }
        List<TeachingAssignmentGroup> sem5Groups = groupRepo.findWithCourseByTermId(activeTerm.getTermId());
        for (TeachingAssignmentGroup g : sem5Groups) {
            Integer semNo = g.getCourse() != null && g.getCourse().getSemester() != null
                    ? semNoMap.get(g.getCourse().getSemester().getSemesterId()) : null;
            if (semNo != null && semNo == 5) {
                System.out.println("  GROUP groupId=" + g.getGroupId() + " course=" + g.getCourse().getCourseCode()
                        + " name=" + g.getGroupName() + " semester=Sem" + semNo);
                List<TeachingAssignmentGroupMember> members = groupMemberRepo.findAllByGroup_GroupId(g.getGroupId());
                for (TeachingAssignmentGroupMember m : members) {
                    String staffNo = m.getAssignment().getStaff().getStaffNo();
                    String sec = m.getAssignment().getSection().getSectionName();
                    System.out.println("     member: staff=" + staffNo + " section=" + sec
                            + " assignmentId=" + m.getAssignment().getAssignmentId());
                }
            }
        }

        // Also list all combined groups in Sem3 (to know cross-semester combined constraints)
        System.out.println("\n===== SEM3 COMBINED GROUPS (cross-check) =====");
        for (TeachingAssignmentGroup g : sem5Groups) {
            Integer semNo = g.getCourse() != null && g.getCourse().getSemester() != null
                    ? semNoMap.get(g.getCourse().getSemester().getSemesterId()) : null;
            if (semNo != null && semNo == 3) {
                System.out.println("  GROUP groupId=" + g.getGroupId() + " course=" + g.getCourse().getCourseCode()
                        + " name=" + g.getGroupName() + " semester=Sem" + semNo);
                List<TeachingAssignmentGroupMember> members = groupMemberRepo.findAllByGroup_GroupId(g.getGroupId());
                for (TeachingAssignmentGroupMember m : members) {
                    System.out.println("     member: staff=" + m.getAssignment().getStaff().getStaffNo()
                            + " section=" + m.getAssignment().getSection().getSectionName()
                            + " assignmentId=" + m.getAssignment().getAssignmentId());
                }
            }
        }

        // Candidate recipients: FCST and FIS staff with 0 load in Sem3 AND Sem5
        System.out.println("\n===== CANDIDATE RECIPIENTS (unit + no Sem3/Sem5 load) =====");
        Map<String, Set<Integer>> staffSemesters = new TreeMap<>();
        Map<String, String> staffUnit = new TreeMap<>();
        Map<String, String> staffName = new TreeMap<>();
        for (TeachingAssignment ta : allAssignments) {
            Integer semNo = semNoMap.get(ta.getCourse().getSemester().getSemesterId());
            if (semNo == null) continue;
            String staffNo = ta.getStaff().getStaffNo();
            staffSemesters.computeIfAbsent(staffNo, k -> new HashSet<>()).add(semNo);
            staffUnit.put(staffNo, ta.getStaff().getUnit() != null ? ta.getStaff().getUnit().getUnitName() : "N/A");
            staffName.put(staffNo, ta.getStaff().getStaffName());
        }
        for (String staffNo : staffSemesters.keySet().stream().sorted().collect(Collectors.toList())) {
            Set<Integer> sems = staffSemesters.get(staffNo);
            String unit = staffUnit.get(staffNo);
            if (unit.contains("Computer Systems") || unit.contains("Information Science")) {
                boolean inSem3 = sems.contains(3);
                boolean inSem5 = sems.contains(5);
                System.out.println("  " + staffNo + " (" + staffName.get(staffNo) + ") unit=" + unit
                        + " sems=" + sems + " inSem3=" + inSem3 + " inSem5=" + inSem5);
            }
        }
    }
}
