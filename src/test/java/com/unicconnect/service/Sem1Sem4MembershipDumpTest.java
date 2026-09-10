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
public class Sem1Sem4MembershipDumpTest {

    @Autowired AcademicTermRepository termRepository;
    @Autowired TeachingAssignmentGroupRepository groupRepository;
    @Autowired TeachingAssignmentRepository assignmentRepository;
    @Autowired CourseRepository courseRepository;

    @Test
    @Transactional
    void dumpForCorrection() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst().orElseThrow();
        Set<String> courseCodes = Set.of("CST-1141","E-1101","M-1101","CST-2241","E-2201");

        System.out.println("\n===== TEACHING ASSIGNMENTS (target courses) =====");
        List<Course> courses = courseRepository.findAll().stream()
                .filter(c -> courseCodes.contains(c.getCourseCode()))
                .toList();
        for (Course c : courses) {
            int sem = c.getSemester() != null ? c.getSemester().getSemesterNo() : -1;
            for (TeachingAssignment ta : assignmentRepository.findByCourse_CourseId(c.getCourseId())) {
                if (ta.getTerm() != null && !ta.getTerm().getTermId().equals(term.getTermId())) continue;
                System.out.println("  " + c.getCourseCode() + " Sem-" + sem
                        + " sec=" + (ta.getSection()!=null?ta.getSection().getSectionName():"?")
                        + " asgId=" + ta.getAssignmentId()
                        + " status=" + ta.getAssignmentStatus());
            }
        }

        System.out.println("\n===== CURRENT COMBINED GROUPS (target courses) =====");
        List<TeachingAssignmentGroup> groups = groupRepository.findWithCourseByTermId(term.getTermId());
        for (TeachingAssignmentGroup g : groups) {
            Course c = g.getCourse();
            if (!courseCodes.contains(c.getCourseCode())) continue;
            int sem = c.getSemester() != null ? c.getSemester().getSemesterNo() : -1;
            String sections = g.getMembers().stream()
                    .map(m -> (m.getAssignment()!=null && m.getAssignment().getSection()!=null)
                            ? m.getAssignment().getSection().getSectionName() : "?")
                    .sorted().collect(Collectors.joining(","));
            System.out.println("  " + c.getCourseCode() + " Sem-" + sem + " group=" + g.getGroupId()
                    + " sections=[" + sections + "] name=" + g.getGroupName());
        }
    }
}
