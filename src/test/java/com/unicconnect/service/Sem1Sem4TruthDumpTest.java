package com.unicconnect.service;

import com.unicconnect.entity.*;
import com.unicconnect.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

@SpringBootTest
@ActiveProfiles("dev")
public class Sem1Sem4TruthDumpTest {

    @Autowired AcademicTermRepository termRepository;
    @Autowired TeachingAssignmentGroupRepository groupRepository;
    @Autowired TeachingAssignmentGroupMemberRepository groupMemberRepository;
    @Autowired SectionRepository sectionRepository;

    @Test
    void groundTruth() {
        // ALL sections with their names+ids (authoritative, small table)
        System.out.println("\n===== SECTIONS TABLE =====");
        for (Section s : sectionRepository.findAll()) {
            System.out.println("  section_id=" + s.getSectionId() + " name=" + s.getSectionName());
        }

        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst().orElseThrow();
        System.out.println("\n===== COMBINED GROUP MEMBERSHIP (eager-fetch, authoritative) =====");
        Set<String> courseCodes = Set.of("CST-1141","E-1101","M-1101","CST-2241","E-2201");
        List<TeachingAssignmentGroupMember> members = groupMemberRepository.findWithDetailsByTermId(term.getTermId());
        Map<UUID, GroupInfo> byGroup = new LinkedHashMap<>();
        for (TeachingAssignmentGroupMember m : members) {
            Course c = m.getAssignment().getCourse();
            if (!courseCodes.contains(c.getCourseCode())) continue;
            UUID gid = m.getGroup().getGroupId();
            GroupInfo gi = byGroup.computeIfAbsent(gid, k -> new GroupInfo());
            gi.course = c.getCourseCode();
            gi.termId = m.getGroup().getTerm().getTermId();
            Semester sem = c.getSemester();
            gi.sem = sem != null ? sem.getSemesterNo() : -1;
            Section sec = m.getAssignment().getSection();
            gi.sections.add(sec != null ? sec.getSectionName() : "?");
            gi.memberSecIds.add(sec != null ? sec.getSectionId().toString().substring(0,8) : "?");
        }
        for (Map.Entry<UUID, GroupInfo> e : byGroup.entrySet()) {
            GroupInfo gi = e.getValue();
            List<String> sorted = new ArrayList<>(gi.sections);
            Collections.sort(sorted);
            System.out.println("  group=" + e.getKey() + " course=" + gi.course + " Sem-" + gi.sem
                    + " memberSections=[" + String.join(",", sorted) + "]");
        }
    }

    static class GroupInfo {
        String course;
        UUID termId;
        int sem;
        Set<String> sections = new TreeSet<>();
        Set<String> memberSecIds = new TreeSet<>();
    }
}
