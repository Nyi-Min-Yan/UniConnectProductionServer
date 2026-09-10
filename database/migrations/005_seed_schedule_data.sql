-- 005_seed_schedule_data.sql
-- Seeds time slots, year-1 course meeting requirements and teaching assignments
-- for the active academic term so timetable generation works out of the box.

-- 1. Time slots (8 periods, on the hour so they align with the frontend grid)
INSERT INTO time_slots (slot_id, period_no, start_time, end_time, display_order)
SELECT md5('slot-' || p.period)::uuid, p.period, p.start_time, p.end_time, p.period
FROM (VALUES
  (1, '08:00:00'::time, '09:00:00'::time),
  (2, '09:00:00'::time, '10:00:00'::time),
  (3, '10:00:00'::time, '11:00:00'::time),
  (4, '11:00:00'::time, '12:00:00'::time),
  (5, '13:00:00'::time, '14:00:00'::time),
  (6, '14:00:00'::time, '15:00:00'::time),
  (7, '15:00:00'::time, '16:00:00'::time),
  (8, '16:00:00'::time, '17:00:00'::time)
) AS p(period, start_time, end_time)
ON CONFLICT (slot_id) DO NOTHING;

-- 2. Course meeting requirements for year-1 courses (semester_no 1 and 2)
-- Technical courses get 2 lectures/week; language courses get 1.
INSERT INTO course_meeting_requirements
    (requirement_id, course_id, meeting_type, sessions_per_week, periods_per_session)
SELECT md5('req-' || c.course_code || '-LECTURE')::uuid, c.course_id, 'LECTURE',
       CASE WHEN c.course_code LIKE 'E-%' OR c.course_code LIKE 'M-%' THEN 1 ELSE 2 END,
       1
FROM courses c
JOIN semesters s ON s.semester_id = c.semester_id
WHERE s.semester_no IN (1, 2)
ON CONFLICT (requirement_id) DO NOTHING;

-- 3. Teaching assignments for the active term
-- Year-1 courses rotated across lecturers STF001..STF003 and sections A..C.
INSERT INTO teaching_assignments
    (assignment_id, course_id, staff_id, section_id, term_id, assignment_status, assigned_at)
SELECT md5('asg-' || c.course_code)::uuid,
       c.course_id,
       st.staff_id,
       sec.section_id,
       t.term_id,
       'PENDING',
       now()
FROM (
    SELECT c2.course_id, c2.course_code,
           row_number() OVER (ORDER BY c2.course_code) AS rn
    FROM courses c2
    JOIN semesters s2 ON s2.semester_id = c2.semester_id
    WHERE s2.semester_no IN (1, 2)
) c
JOIN (SELECT term_id FROM academic_terms ORDER BY academic_year DESC LIMIT 1) t ON true
JOIN staff st ON st.staff_no = 'STF00' || ((c.rn - 1) % 3 + 1)
JOIN sections sec ON sec.section_name = chr(65 + ((c.rn - 1) % 3)::integer)
ON CONFLICT (assignment_id) DO NOTHING;
