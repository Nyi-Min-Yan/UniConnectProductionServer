-- ============================================================
-- Migration 019: Semester 4 Combined-Class & Lecturer Fix
-- ============================================================
-- This script:
--   PART 1: Inspects current Semester 4 database state
--   PART 2: Corrects CST-2241 and E-2201 combined groups to A+B+CT
--   PART 3-4: Identifies real lecturer conflicts
--   PART 5-6: Creates additional lecturer(s) and reassigns
--   PART 7-8: Uses transaction safety
--   PART 9-10: Post-update verification
--
-- TARGET: Semester 4 courses (CS-2256, CST-2212, CST-2213,
--         CST-2224, CST-2235, CST-2241, E-2201)
-- ============================================================

-- ============================================================
-- PART 1: INSPECT CURRENT DATABASE STATE
-- ============================================================

-- 1a. Active academic term
SELECT '=== ACTIVE ACADEMIC TERM ===' AS section;
SELECT term_id, academic_year, start_date, end_date, status
FROM academic_terms
WHERE status = 'ACTIVE';

-- 1b. Semester 4 info
SELECT '=== SEMESTER 4 ===' AS section;
SELECT semester_id, semester_no FROM semesters WHERE semester_no = 4;

-- 1c. All sections
SELECT '=== ALL SECTIONS ===' AS section;
SELECT section_id, section_name FROM sections ORDER BY section_name;

-- 1d. Semester 4 courses with unit and major
SELECT '=== SEMESTER 4 COURSES ===' AS section;
SELECT c.course_id, c.course_code, c.course_name, c.credit_unit,
       u.unit_code, u.unit_name, m.major_code, s.semester_no
FROM courses c
JOIN semesters s ON s.semester_id = c.semester_id
JOIN organizational_units u ON u.unit_id = c.unit_id
LEFT JOIN majors m ON m.major_id = c.major_id
WHERE s.semester_no = 4
ORDER BY c.course_code;

-- 1e. Teaching assignments for Semester 4 courses
SELECT '=== SEMESTER 4 TEACHING ASSIGNMENTS ===' AS section;
SELECT ta.assignment_id, c.course_code, sec.section_name,
       st.staff_no, st.staff_name, u.unit_code AS staff_dept,
       ta.assignment_status, ta.term_id
FROM teaching_assignments ta
JOIN courses c ON c.course_id = ta.course_id
JOIN semesters s ON s.semester_id = c.semester_id
JOIN sections sec ON sec.section_id = ta.section_id
JOIN staff st ON st.staff_id = ta.staff_id
LEFT JOIN organizational_units u ON u.unit_id = st.unit_id
WHERE s.semester_no = 4
  AND ta.assignment_status != 'CANCELLED'
ORDER BY c.course_code, sec.section_name;

-- 1f. All existing combined groups (teaching_assignment_groups)
SELECT '=== ALL COMBINED GROUPS ===' AS section;
SELECT tag.group_id, tag.group_name, c.course_code, s.semester_no,
       tag.term_id, tag.created_at
FROM teaching_assignment_groups tag
JOIN courses c ON c.course_id = tag.course_id
JOIN semesters s ON s.semester_id = c.semester_id
ORDER BY s.semester_no, c.course_code;

-- 1g. Combined group members
SELECT '=== ALL COMBINED GROUP MEMBERS ===' AS section;
SELECT tagm.group_id, tag.group_name, c.course_code, s.semester_no,
       sec.section_name, st.staff_no, st.staff_name,
       ta.assignment_id, ta.assignment_status
FROM teaching_assignment_group_members tagm
JOIN teaching_assignment_groups tag ON tag.group_id = tagm.group_id
JOIN courses c ON c.course_id = tag.course_id
JOIN semesters s ON s.semester_id = c.semester_id
JOIN teaching_assignments ta ON ta.assignment_id = tagm.assignment_id
JOIN sections sec ON sec.section_id = ta.section_id
JOIN staff st ON st.staff_id = ta.staff_id
ORDER BY s.semester_no, c.course_code, sec.section_name;

-- 1h. Semester 4 course meeting requirements
SELECT '=== SEMESTER 4 CMRs ===' AS section;
SELECT cmr.course_id, c.course_code, cmr.meeting_type,
       cmr.sessions_per_week, cmr.periods_per_session,
       (cmr.sessions_per_week * cmr.periods_per_session) AS total_weekly_periods
FROM course_meeting_requirements cmr
JOIN courses c ON c.course_id = cmr.course_id
JOIN semesters s ON s.semester_id = c.semester_id
WHERE s.semester_no = 4
ORDER BY c.course_code, cmr.meeting_type;

-- 1i. Staff assigned to Semester 4 courses
SELECT '=== STAFF ON SEMESTER 4 COURSES ===' AS section;
SELECT DISTINCT st.staff_id, st.staff_no, st.staff_name,
       u.unit_code AS staff_dept,
       STRING_AGG(DISTINCT c.course_code, ', ' ORDER BY c.course_code) AS courses_taught,
       COUNT(DISTINCT c.course_id) AS num_courses
FROM teaching_assignments ta
JOIN courses c ON c.course_id = ta.course_id
JOIN semesters s ON s.semester_id = c.semester_id
JOIN staff st ON st.staff_id = ta.staff_id
LEFT JOIN organizational_units u ON u.unit_id = st.unit_id
WHERE s.semester_no = 4
  AND ta.assignment_status != 'CANCELLED'
GROUP BY st.staff_id, st.staff_no, st.staff_name, u.unit_code
ORDER BY st.staff_no;

-- 1j. Staff teaching同一 course across DIFFERENT semesters
SELECT '=== STAFF WITH CROSS-SEMESTER ASSIGNMENTS ===' AS section;
SELECT st.staff_no, st.staff_name, s.semester_no,
       STRING_AGG(DISTINCT c.course_code, ', ' ORDER BY c.course_code) AS courses
FROM teaching_assignments ta
JOIN courses c ON c.course_id = ta.course_id
JOIN semesters s ON s.semester_id = c.semester_id
JOIN staff st ON st.staff_id = ta.staff_id
WHERE ta.assignment_status != 'CANCELLED'
GROUP BY st.staff_no, st.staff_name, s.semester_no
ORDER BY st.staff_no, s.semester_no;

-- ============================================================
-- PART 1 END
-- ============================================================
-- STOP HERE AND REVIEW THE OUTPUT before proceeding.
-- The queries above show the current state.
-- After reviewing, run the correction sections below.
-- ============================================================
