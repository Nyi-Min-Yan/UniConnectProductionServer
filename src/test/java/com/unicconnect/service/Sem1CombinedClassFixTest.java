package com.unicconnect.service;

import com.unicconnect.entity.*;
import com.unicconnect.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

/**
 * USER-AUTHORIZED data correction (NOT a solver change):
 * The Sem-1 combined classes (CST-1141, E-1101, M-1101) currently span A+B+C,
 * but the requested Mid Term scope selects Sem-1 as A+B (C unchecked). Per the
 * combined-class rule, selecting only A+B of an A+B+C group must be REJECTED as
 * partially selected, so this exact scope could never generate.
 *
 * Fix: remove the section-C member from each Sem-1 combined class, yielding A+B
 * (the same shape as the Sem-3 combined classes). This is persisted (committed).
 *
 * NOTE: Sem-4 (CST-2241, E-2201) is verified separately; ground-truth eager-fetch
 * shows it is ALREADY A+B+CT, so no Sem-4 change is needed.
 */
@SpringBootTest
@ActiveProfiles("dev")
public class Sem1CombinedClassFixTest {

    @Autowired AcademicTermRepository termRepository;
    @Autowired TeachingAssignmentGroupRepository groupRepository;
    @Autowired TeachingAssignmentGroupMemberRepository groupMemberRepository;

    private static final String C_SECTION = "C";

    @Test
    void fixSem1CombinedClasses() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst().orElseThrow();
        Set<String> sem1Courses = Set.of("CST-1141", "E-1101", "M-1101");

        // Eager-fetch authoritative membership
        List<TeachingAssignmentGroupMember> members =
                groupMemberRepository.findWithDetailsByTermId(term.getTermId());

        Map<String, String> beforeSections = new LinkedHashMap<>();
        Map<String, String> afterSections = new LinkedHashMap<>();
        // courseCode -> (group course code/sem)
        Map<UUID, String> courseByGroup = new HashMap<>();
        // map group -> its members
        Map<UUID, List<TeachingAssignmentGroupMember>> byGroup = new HashMap<>();
        for (TeachingAssignmentGroupMember m : members) {
            String cc = m.getAssignment().getCourse().getCourseCode();
            Semester sem = m.getAssignment().getCourse().getSemester();
            if (!sem1Courses.contains(cc)) continue;
            if (sem == null || sem.getSemesterNo() != 1) continue;
            byGroup.computeIfAbsent(m.getGroup().getGroupId(), k -> new ArrayList<>()).add(m);
        }

        int removed = 0;
        for (Map.Entry<UUID, List<TeachingAssignmentGroupMember>> e : byGroup.entrySet()) {
            String cc = e.getValue().get(0).getAssignment().getCourse().getCourseCode();
            List<String> before = new ArrayList<>();
            for (TeachingAssignmentGroupMember m : e.getValue()) {
                before.add(m.getAssignment().getSection() != null
                        ? m.getAssignment().getSection().getSectionName() : "?");
            }
            Collections.sort(before);
            beforeSections.put(cc, "[" + String.join(",", before) + "]");

            // remove members whose section == C
            for (TeachingAssignmentGroupMember m : new ArrayList<>(e.getValue())) {
                String sec = m.getAssignment().getSection() != null
                        ? m.getAssignment().getSection().getSectionName() : null;
                if (C_SECTION.equals(sec)) {
                    groupMemberRepository.deleteById(m.getId());
                    removed++;
                    System.out.println("  REMOVED section-C member from " + cc
                            + " (assignment=" + m.getAssignment().getAssignmentId() + ")");
                }
            }
            flushClear();

            // re-read after
            List<TeachingAssignmentGroupMember> afterList =
                    groupMemberRepository.findWithDetailsByGroupId(e.getKey());
            List<String> after = new ArrayList<>();
            for (TeachingAssignmentGroupMember m : afterList) {
                after.add(m.getAssignment().getSection() != null
                        ? m.getAssignment().getSection().getSectionName() : "?");
            }
            Collections.sort(after);
            afterSections.put(cc, "[" + String.join(",", after) + "]");
        }

        System.out.println("\n===== SEM-1 COMBINED-CLASS FIX RESULT =====");
        for (String cc : new ArrayList<>(beforeSections.keySet())) {
            System.out.println("  " + cc + ": " + beforeSections.get(cc) + " -> " + afterSections.get(cc));
        }
        System.out.println("  removed=" + removed);
        System.out.println("  FIX APPLIED (persisted).");
    }

    private void flushClear() {
        groupMemberRepository.flush();
    }
}
