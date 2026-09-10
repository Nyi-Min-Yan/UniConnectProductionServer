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
public class StaffAvailabilityAuditTest {

    @Autowired private TeachingAssignmentRepository taRepo;
    @Autowired private AcademicTermRepository termRepo;
    @Autowired private SemesterRepository semRepo;
    @Autowired private StaffRepository staffRepo;

    @Test
    public void fullStaffInventory() {
        AcademicTerm activeTerm = termRepo.findAll().stream()
                .filter(t -> t.getStatus() == TermStatus.ACTIVE)
                .findFirst().orElseThrow(() -> new RuntimeException("No active term"));
        List<TeachingAssignment> allAssignments = taRepo.findWithDetailsByTermId(activeTerm.getTermId());
        Map<UUID, Integer> semNoMap = semRepo.findAll().stream()
                .collect(Collectors.toMap(Semester::getSemesterId, Semester::getSemesterNo));

        // Group assignments by staffNo -> sem -> course
        Map<String, Map<Integer, Set<String>>> staffLoad = new LinkedHashMap<>();
        Map<String, Staff> staffById = new LinkedHashMap<>();

        for (TeachingAssignment ta : allAssignments) {
            Integer semNo = semNoMap.get(ta.getCourse().getSemester().getSemesterId());
            if (semNo == null) continue;
            String staffNo = ta.getStaff().getStaffNo();
            staffById.putIfAbsent(staffNo, ta.getStaff());
            staffLoad.computeIfAbsent(staffNo, k -> new TreeMap<>())
                    .computeIfAbsent(semNo, k -> new LinkedHashSet<>())
                    .add(ta.getCourse().getCourseCode());
        }

        System.out.println("===== FULL STAFF INVENTORY (ALL STAFF IN DB) =====");
        System.out.printf("%-10s %-24s %-45s%n", "StaffNo", "Name", "Unit");
        System.out.println("-".repeat(100));

        List<Staff> allStaff = staffRepo.findAll();
        for (Staff st : allStaff.stream().sorted(Comparator.comparing(Staff::getStaffNo)).collect(Collectors.toList())) {
            String unit = st.getUnit() != null ? st.getUnit().getUnitName() : "N/A";
            String userEmail = st.getUser() != null ? st.getUser().getEmail() : "";
            System.out.printf("%-10s %-24s %-45s user=%s%n",
                    st.getStaffNo(), st.getStaffName(), unit, userEmail);
        }

        System.out.println("\n===== STAFF ACTUAL COURSE LOADS (from teaching_assignments) =====");
        System.out.println("Format: SemN = course1,course2");
        for (String staffNo : staffLoad.keySet().stream().sorted().collect(Collectors.toList())) {
            Staff st = staffById.get(staffNo);
            String unit = st.getUnit() != null ? st.getUnit().getUnitName() : "N/A";
            String name = st.getStaffName();
            StringBuilder sb = new StringBuilder();
            int totalCourses = 0;
            for (int s = 1; s <= 8; s++) {
                Set<String> courses = staffLoad.get(staffNo).get(s);
                sb.append("Sem").append(s).append("=[");
                if (courses != null) {
                    sb.append(String.join(",", courses));
                    totalCourses += courses.size();
                }
                sb.append("] ");
            }
            System.out.println(staffNo + " (" + name + ") [" + unit + "] totalCourseAssignments=" + totalCourses);
            System.out.println("   " + sb.toString().trim());
        }
    }
}
