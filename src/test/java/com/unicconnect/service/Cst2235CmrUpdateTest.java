package com.unicconnect.service;

import com.unicconnect.entity.Course;
import com.unicconnect.entity.CourseMeetingRequirement;
import com.unicconnect.entity.MeetingType;
import com.unicconnect.repository.CourseMeetingRequirementRepository;
import com.unicconnect.repository.CourseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@SpringBootTest
@ActiveProfiles("dev")
public class Cst2235CmrUpdateTest {

    @Autowired private CourseRepository courseRepo;
    @Autowired private CourseMeetingRequirementRepository cmrRepo;

    @Test
    @Transactional
    @Commit
    public void setCst2235CmrsTo1x2() {
        Course c = courseRepo.findByCourseCode("CST-2235")
                .orElseThrow(() -> new RuntimeException("CST-2235 not found"));

        System.out.println("=== BEFORE ===");
        for (CourseMeetingRequirement r : cmrRepo.findByCourse_CourseId(c.getCourseId())) {
            System.out.printf("  %s : sessions_per_week=%d periods_per_session=%d%n",
                    r.getMeetingType(), r.getSessionsPerWeek(), r.getPeriodsPerSession());
        }

        for (MeetingType type : new MeetingType[]{MeetingType.LAB, MeetingType.LECTURE}) {
            CourseMeetingRequirement r = cmrRepo.findByCourse_CourseIdAndMeetingType(c.getCourseId(), type)
                    .orElseGet(() -> {
                        CourseMeetingRequirement n = new CourseMeetingRequirement();
                        n.setCourse(c);
                        n.setMeetingType(type);
                        return n;
                    });
            r.setSessionsPerWeek(1);
            r.setPeriodsPerSession(2);
            cmrRepo.save(r);
            System.out.printf("  SET %s -> sessions_per_week=1 periods_per_session=2%n", type);
        }
        cmrRepo.flush();

        System.out.println("=== AFTER ===");
        List<CourseMeetingRequirement> all = cmrRepo.findByCourse_CourseId(c.getCourseId());
        for (CourseMeetingRequirement r : all) {
            System.out.printf("  %s : sessions_per_week=%d periods_per_session=%d%n",
                    r.getMeetingType(), r.getSessionsPerWeek(), r.getPeriodsPerSession());
        }
    }
}