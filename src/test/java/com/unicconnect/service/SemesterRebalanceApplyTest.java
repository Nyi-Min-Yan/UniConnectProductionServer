package com.unicconnect.service;

import com.unicconnect.entity.*;
import com.unicconnect.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@SpringBootTest
@ActiveProfiles("dev")
public class SemesterRebalanceApplyTest {

    @Autowired private TeachingAssignmentRepository taRepo;
    @Autowired private AcademicTermRepository termRepo;
    @Autowired private SemesterRepository semRepo;
    @Autowired private StaffRepository staffRepo;
    @Autowired private TeachingAssignmentGroupMemberRepository groupMemberRepo;

    @Test
    @Transactional
    @Commit
    public void applyRebalancing() {
        AcademicTerm activeTerm = termRepo.findAll().stream()
                .filter(t -> t.getStatus() == TermStatus.ACTIVE)
                .findFirst().orElseThrow(() -> new RuntimeException("No active term"));
        UUID termId = activeTerm.getTermId();

        List<TeachingAssignment> allAssignments = taRepo.findWithDetailsByTermId(termId);

        // Locate recipients by staffNo
        Staff stf049 = findStaff("STF049");
        Staff stf016 = findStaff("STF016");

        System.out.println("Recipients: STF049=" + stf049.getStaffName() + ", STF016=" + stf016.getStaffName());

        int change1 = 0, change2 = 0;

        for (TeachingAssignment ta : allAssignments) {
            // CHANGE 1: move CST-3136 (Sem5, all sections) from STF014 -> STF049
            if ("STF014".equals(ta.getStaff().getStaffNo())
                    && "CST-3136".equals(ta.getCourse().getCourseCode())) {
                if (groupMemberRepo.existsByAssignment_AssignmentId(ta.getAssignmentId())) {
                    throw new IllegalStateException("Cannot move CST-3136 assignment "
                            + ta.getAssignmentId() + " - it is a combined group member");
                }
                String before = ta.getStaff().getStaffNo();
                ta.setStaff(stf049);
                taRepo.save(ta);
                System.out.println("CHANGE1: CST-3136 section=" + ta.getSection().getSectionName()
                        + " reassigned " + before + " -> STF049");
                change1++;
            }

            // CHANGE 2: move CS-3125 (B) and CS-3124 (A,C) from STF0034 -> STF016
            if ("STF0034".equals(ta.getStaff().getStaffNo())
                    && ("CS-3125".equals(ta.getCourse().getCourseCode())
                        || "CS-3124".equals(ta.getCourse().getCourseCode()))) {
                if (groupMemberRepo.existsByAssignment_AssignmentId(ta.getAssignmentId())) {
                    throw new IllegalStateException("Cannot move " + ta.getCourse().getCourseCode()
                            + " assignment " + ta.getAssignmentId() + " - it is a combined group member");
                }
                String before = ta.getStaff().getStaffNo();
                ta.setStaff(stf016);
                taRepo.save(ta);
                System.out.println("CHANGE2: " + ta.getCourse().getCourseCode()
                        + " section=" + ta.getSection().getSectionName()
                        + " reassigned " + before + " -> STF016");
                change2++;
            }
        }

        taRepo.flush();

        System.out.println("Total moves: change1=" + change1 + " (CST-3136), change2=" + change2 + " (CS-3124/CS-3125)");

        // VERIFY POST-STATE
        System.out.println("\n===== POST-CHANGE VERIFICATION (Sem5) =====");
        Map<UUID, Integer> semNoMap = semRepo.findAll().stream()
                .collect(Collectors.toMap(Semester::getSemesterId, Semester::getSemesterNo));
        List<TeachingAssignment> refreshed = taRepo.findWithDetailsByTermId(termId);

        // Verify no STF014/STF0034 remains in Sem5
        for (TeachingAssignment ta : refreshed) {
            Integer semNo = semNoMap.get(ta.getCourse().getSemester().getSemesterId());
            if (semNo != null && semNo == 5) {
                String staffNo = ta.getStaff().getStaffNo();
                if (staffNo.equals("STF014") || staffNo.equals("STF0034")) {
                    throw new IllegalStateException("STILL in Sem5: " + staffNo + " " + ta.getCourse().getCourseCode()
                            + " section=" + ta.getSection().getSectionName());
                }
            }
        }
        System.out.println("OK: No STF014/STF0034 remains in any Sem5 assignment.");

        // New Sem5 load summary by lecturer
        Map<String, Set<String>> sem5ByStaff = new TreeMap<>();
        Map<String, String> staffName = new HashMap<>();
        for (TeachingAssignment ta : refreshed) {
            Integer semNo = semNoMap.get(ta.getCourse().getSemester().getSemesterId());
            if (semNo != null && semNo == 5) {
                String sn = ta.getStaff().getStaffNo();
                staffName.put(sn, ta.getStaff().getStaffName());
                sem5ByStaff.computeIfAbsent(sn, k -> new LinkedHashSet<>()).add(ta.getCourse().getCourseCode());
            }
        }
        System.out.printf("%-10s %-22s %-15s%n", "StaffNo", "Name", "Sem5 Courses");
        for (String sn : sem5ByStaff.keySet()) {
            System.out.printf("%-10s %-22s %-15s%n", sn, staffName.get(sn), sem5ByStaff.get(sn));
        }
    }

    private Staff findStaff(String staffNo) {
        return staffRepo.findAll().stream()
                .filter(s -> s.getStaffNo().equals(staffNo))
                .findFirst().orElseThrow(() -> new RuntimeException("Staff not found: " + staffNo));
    }
}
