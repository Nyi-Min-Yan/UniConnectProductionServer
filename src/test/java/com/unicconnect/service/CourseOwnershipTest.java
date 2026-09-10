package com.unicconnect.service;

import com.unicconnect.entity.Course;
import com.unicconnect.repository.CourseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression suite for the authoritative course-code-prefix ownership rule:
 * CS-* -> CS, CST-* -> CST, CT-* -> CT, every other (general/shared) course -> CST.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional(readOnly = true)
public class CourseOwnershipTest {

    @Autowired
    CourseRepository courseRepository;

    private String expectedMajorCode(String courseCode) {
        if (courseCode.startsWith("CS-")) return "CS";
        if (courseCode.startsWith("CST-")) return "CST";
        if (courseCode.startsWith("CT-")) return "CT";
        return "CST";
    }

    // A + B + C: every course in the database follows the prefix rule, no NULLs
    @Test
    void everyCourseFollowsPrefixOwnershipRule() {
        List<Course> courses = courseRepository.findAll();
        assertTrue(courses.size() >= 60, "curriculum should be seeded, found " + courses.size());
        for (Course course : courses) {
            String code = course.getCourseCode();
            assertNotNull(course.getMajor(), "course " + code + " has NULL major_id");
            assertEquals(expectedMajorCode(code), course.getMajor().getMajorCode(),
                    "course " + code + " has wrong major");
            assertNotNull(course.getUnit(), "course " + code + " has NULL unit_id");
        }
    }

    // A: specific prefix-ownership examples from the business rules
    @Test
    void specificPrefixOwnershipExamples() {
        assertOwnedBy("CS-2256", "CS");
        assertOwnedBy("CS-3124", "CS");
        assertOwnedBy("CST-1102", "CST");
        assertOwnedBy("CST-1234", "CST");
        assertOwnedBy("CT-2234", "CT");
    }

    // B: general/shared courses are owned by CST
    @Test
    void generalSharedCoursesAreOwnedByCst() {
        for (String code : List.of("E-1101", "E-1201", "E-2101", "E-2201", "E-4101",
                "M-1101", "M-1201", "P-1101", "P-1201")) {
            Course course = courseRepository.findByCourseCode(code)
                    .orElseThrow(() -> new AssertionError("course " + code + " missing from database"));
            assertEquals("CST", course.getMajor().getMajorCode(),
                    code + " must be owned by CST");
        }
    }

    private void assertOwnedBy(String courseCode, String expectedMajorCode) {
        Course course = courseRepository.findByCourseCode(courseCode)
                .orElseThrow(() -> new AssertionError("course " + courseCode + " missing from database"));
        assertEquals(expectedMajorCode, course.getMajor().getMajorCode(),
                courseCode + " has wrong major");
    }
}
