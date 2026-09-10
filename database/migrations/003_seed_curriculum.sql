-- ============================================================
-- UniConnect - University Management System
-- Migration 003: Seed curriculum reference data (semesters,
--                 organizational units, majors, courses)
-- Target: Neon PostgreSQL
--
-- DESIGN:
--   * Idempotent: safe to run any number of times.
--   * Duplicate prevention via natural keys:
--       - semesters              -> semester_no
--       - organizational_units   -> unit_code (or unit_name if a
--                                   same-named unit already exists)
--       - majors                 -> major_code
--       - courses                -> course_code (UNIQUE constraint +
--                                   ON CONFLICT DO NOTHING)
--   * No hardcoded UUIDs: all FKs are resolved by lookups.
--   * Existing rows are NEVER updated; missing rows are inserted.
--   * No schema changes: works with the 001_initial_schema.sql layout.
--
-- COURSE -> SEMESTER RULE:
--   The first two digits of the numeric part of the course code are
--   authoritative (NOT the section heading):
--       first digit  = year   (1..4)
--       second digit = semester within that year (1 or 2)
--   semester_no = (year - 1) * 2 + (semester within year)
--   e.g. CST-1102 -> 1,1 -> semester 1 | CST-4137 -> 4,1 -> semester 7
-- ============================================================

BEGIN;

-- ============================================================
-- 1. SEMESTERS (1..8)
-- ============================================================
INSERT INTO semesters (semester_no)
SELECT g.sem
FROM generate_series(1, 8) AS g(sem)
WHERE NOT EXISTS (
    SELECT 1 FROM semesters s WHERE s.semester_no = g.sem
);

-- ============================================================
-- 2. ORGANIZATIONAL UNITS
-- ============================================================
-- unit_type is normalized to: ACADEMIC (faculties + academic
-- departments) and ADMINISTRATIVE (administration/finance/student
-- affairs) - enforced by chk_organizational_units_unit_type (004).
INSERT INTO organizational_units (unit_name, unit_code, unit_type)
SELECT v.unit_name, v.unit_code, v.unit_type
FROM (VALUES
    ('Faculty of Computer Science',                          'FCS',          'ACADEMIC'),
    ('Faculty of Computer Systems and Technologies',         'FCST',         'ACADEMIC'),
    ('Faculty of Information Science',                       'FIS',          'ACADEMIC'),
    ('Department of Information Technologies Support and Maintenance', 'ITSM', 'ACADEMIC'),
    ('Faculty of Computing',                                 'FC',           'ACADEMIC'),
    ('Department of Natural Language',                       'DNL',          'ACADEMIC'),
    ('Department of Natural Science',                        'DNS',          'ACADEMIC'),
    ('Department of Administration',                         'ADMIN',        'ADMINISTRATIVE'),
    ('Department of Finance',                                'FINANCE',      'ADMINISTRATIVE'),
    ('Department of Student Affairs',                        'STUDENT_AFFAIRS', 'ADMINISTRATIVE')
) AS v(unit_name, unit_code, unit_type)
WHERE NOT EXISTS (
    -- Skip if the same unit already exists under ANY code
    SELECT 1 FROM organizational_units u
    WHERE u.unit_name = v.unit_name OR u.unit_code = v.unit_code
);

-- ============================================================
-- 3. MAJORS
-- ============================================================
INSERT INTO majors (unit_id, major_code, major_name)
SELECT u.unit_id, v.major_code, v.major_name
FROM (VALUES
    ('CS',  'Computer Science',                'Faculty of Computer Science'),
    ('CT',  'Computer Technology',             'Faculty of Computer Systems and Technologies'),
    ('CST', 'Computer Science and Technology', 'Faculty of Information Science')
) AS v(major_code, major_name, unit_name)
JOIN organizational_units u ON u.unit_name = v.unit_name
WHERE NOT EXISTS (
    SELECT 1 FROM majors m WHERE m.major_code = v.major_code
);

-- ============================================================
-- 4. COURSES
-- ============================================================
-- credit_unit: 3 (no project default exists; sensible seed value)
-- is_required: TRUE for the curriculum
-- display_order: 1-based, sequential within each unit+semester block

-- ------------------------------------------------------------
-- 4a. FCS - Faculty of Computer Science (major: CS)
-- ------------------------------------------------------------
INSERT INTO courses (unit_id, course_code, course_name, credit_unit, major_id, semester_id, is_required, display_order)
SELECT
    u.unit_id, c.course_code, c.course_name, 3, m.major_id, sem.semester_id, TRUE, c.display_order
FROM (VALUES
    ('CST-1102', 'Principle of Information Technology', 1),
    ('CST-2112', 'Data Structures and Algorithms',       2),
    ('CST-2113', 'Programming Language in Java',         3),
    ('CST-3112', 'Professional Ethics',                  4),
    ('CST-3113', 'Analysis of Algorithms',               5),
    ('CST-4112', 'Parallel and Distributed Computing',   6),
    ('CS-4115',  'Advanced Artificial Intelligence',     7),
    ('CST-4137', 'Emerging Technologies II',             8),
    ('CST-1212', 'Programming Logic & Design (Programming in C++)', 1),
    ('CST-2212', 'Artificial Intelligence',              2),
    ('CST-2213', 'Operating Systems',                    3),
    ('CS-3212',  'Computer Vision',                      4),
    ('CS-3215',  'Advanced Artificial Intelligence',     5),
    ('CST-3217', 'Emerging Technologies',                6)
) AS c(course_code, course_name, display_order)
CROSS JOIN LATERAL (
    SELECT unit_id FROM organizational_units
    WHERE unit_name = 'Faculty of Computer Science' OR unit_code = 'FCS'
    LIMIT 1
) u
LEFT JOIN majors m ON m.major_code = CASE
    WHEN c.course_code LIKE 'CS-%' THEN 'CS'
    WHEN c.course_code LIKE 'CST-%' THEN 'CST'
    WHEN c.course_code LIKE 'CT-%' THEN 'CT'
    ELSE 'CST'
END
CROSS JOIN LATERAL (
    SELECT CAST(SUBSTRING(c.course_code FROM '[0-9]+') AS INTEGER) AS n
) d
JOIN semesters sem ON sem.semester_no = (d.n / 1000 - 1) * 2 + (d.n / 100 % 10)
ON CONFLICT (course_code) DO NOTHING;

-- ------------------------------------------------------------
-- 4b. FCST - Faculty of Computer Systems and Technologies (major: CT for CT-*, CST for CST-*)
-- ------------------------------------------------------------
INSERT INTO courses (unit_id, course_code, course_name, credit_unit, major_id, semester_id, is_required, display_order)
SELECT
    u.unit_id, c.course_code, c.course_name, 3, m.major_id, sem.semester_id, TRUE, c.display_order
FROM (VALUES
    ('CST-2135', 'Computer Architecture & Organization',           1),
    ('CST-3136', 'Computer Networks',                              2),
    ('CT-3134',  'Electronic Devices',                             3),
    ('CT-3135',  'Control Systems',                                4),
    ('CT-3137',  'Signals and Systems',                            5),
    ('CT-4131',  'Cyber Security',                                 6),
    ('CT-4134',  'Embedded Systems Integrating to IoT',            7),
    ('CT-4125',  'Data Science',                                   8),
    ('CT-4136',  'Digital Forensics',                              9),
    ('CT-4137',  'Embedded Robotics',                             10),
    ('CST-1234', 'Digital and Logic Design',                       1),
    ('CST-2235', 'Data Communication and Networking',              2),
    ('CT-2234',  'Digital System Design',                          3),
    ('CT-2236',  'Circuits and Electronics',                       4),
    ('CT-3231',  'Embedded and Microprocessor Systems',            5),
    ('CT-3232',  'Computer and Network Security',                  6),
    ('CT-3233',  'Image Processing',                               7),
    ('CT-3235',  'Digital Signal Processing',                      8)
) AS c(course_code, course_name, display_order)
CROSS JOIN LATERAL (
    SELECT unit_id FROM organizational_units
    WHERE unit_name = 'Faculty of Computer Systems and Technologies' OR unit_code = 'FCST'
    LIMIT 1
) u
LEFT JOIN majors m ON m.major_code = CASE
    WHEN c.course_code LIKE 'CS-%' THEN 'CS'
    WHEN c.course_code LIKE 'CST-%' THEN 'CST'
    WHEN c.course_code LIKE 'CT-%' THEN 'CT'
    ELSE 'CST'
END
CROSS JOIN LATERAL (
    SELECT CAST(SUBSTRING(c.course_code FROM '[0-9]+') AS INTEGER) AS n
) d
JOIN semesters sem ON sem.semester_no = (d.n / 1000 - 1) * 2 + (d.n / 100 % 10)
ON CONFLICT (course_code) DO NOTHING;

-- ------------------------------------------------------------
-- 4c. FIS - Faculty of Information Science (major: CST for CST-*, CS for CS-*)
-- ------------------------------------------------------------
INSERT INTO courses (unit_id, course_code, course_name, credit_unit, major_id, semester_id, is_required, display_order)
SELECT
    u.unit_id, c.course_code, c.course_name, 3, m.major_id, sem.semester_id, TRUE, c.display_order
FROM (VALUES
    ('CST-1123', 'Basic Data Processing',                  1),
    ('CST-2123', 'Software Engineering',                   2),
    ('CST-2126', 'Database Management System',             3),
    ('CS-3124',  'Software Quality Assurance and Testing', 4),
    ('CS-3125',  'Database System Structure',              5),
    ('CST-4123', 'Software Project Management',            6),
    ('CS-4124',  'Information Assurance and Security',     7),
    ('CS-4126',  'Data Science',                           8),
    ('CST-1223', 'Database Fundamentals',                  1),
    ('CST-2224', 'Software Analysis and Design',           2),
    ('CS-3223',  'Software Design and Development',        3),
    ('CST-3226', 'Data Mining',                            4)
) AS c(course_code, course_name, display_order)
CROSS JOIN LATERAL (
    SELECT unit_id FROM organizational_units
    WHERE unit_name = 'Faculty of Information Science' OR unit_code = 'FIS'
    LIMIT 1
) u
LEFT JOIN majors m ON m.major_code = CASE
    WHEN c.course_code LIKE 'CS-%' THEN 'CS'
    WHEN c.course_code LIKE 'CST-%' THEN 'CST'
    WHEN c.course_code LIKE 'CT-%' THEN 'CT'
    ELSE 'CST'
END
CROSS JOIN LATERAL (
    SELECT CAST(SUBSTRING(c.course_code FROM '[0-9]+') AS INTEGER) AS n
) d
JOIN semesters sem ON sem.semester_no = (d.n / 1000 - 1) * 2 + (d.n / 100 % 10)
ON CONFLICT (course_code) DO NOTHING;

-- ------------------------------------------------------------
-- 4d. ITSM - Department of Information Technologies Support
--     and Maintenance (major: CST for CST-*, CS for CS-*)
-- ------------------------------------------------------------
INSERT INTO courses (unit_id, course_code, course_name, credit_unit, major_id, semester_id, is_required, display_order)
SELECT
    u.unit_id, c.course_code, c.course_name, 3, m.major_id, sem.semester_id, TRUE, c.display_order
FROM (VALUES
    ('CST-1154', 'Web Development (HTML5+ CSS)', 1),
    ('CS-3117',  'Web Programming (J2EE)',       2),
    ('CS-3157A', 'Web Programming (PHP)',        3),
    ('CS-3157B', 'Web Programming (C#)',         4),
    ('CST-4158', 'Business Information System',  5),
    ('CS-2256',  'Web Technology (Java Script)', 1),
    ('CST-3254', 'Human Computer Interaction',   2),
    ('CST-3258', 'Business Information System',  3)
) AS c(course_code, course_name, display_order)
CROSS JOIN LATERAL (
    SELECT unit_id FROM organizational_units
    WHERE unit_name = 'Department of Information Technologies Support and Maintenance' OR unit_code = 'ITSM'
    LIMIT 1
) u
LEFT JOIN majors m ON m.major_code = CASE
    WHEN c.course_code LIKE 'CS-%' THEN 'CS'
    WHEN c.course_code LIKE 'CST-%' THEN 'CST'
    WHEN c.course_code LIKE 'CT-%' THEN 'CT'
    ELSE 'CST'
END
CROSS JOIN LATERAL (
    SELECT CAST(SUBSTRING(c.course_code FROM '[0-9]+') AS INTEGER) AS n
) d
JOIN semesters sem ON sem.semester_no = (d.n / 1000 - 1) * 2 + (d.n / 100 % 10)
ON CONFLICT (course_code) DO NOTHING;

-- ------------------------------------------------------------
-- 4e. FC - Faculty of Computing (major: CST for all courses)
-- ------------------------------------------------------------
INSERT INTO courses (unit_id, course_code, course_name, credit_unit, major_id, semester_id, is_required, display_order)
SELECT
    u.unit_id, c.course_code, c.course_name, 3, m.major_id, sem.semester_id, TRUE, c.display_order
FROM (VALUES
    ('CST-1141', 'Calculus',                                1),
    ('CST-2141', 'Linear Algebra',                          2),
    ('CST-3141', 'Probability and Statistics',              3),
    ('CST-4141', 'Modeling and Simulation',                 4),
    ('CST-1241', 'Discrete Mathematics',                    1),
    ('CST-2241', 'Numerical Analysis and Differential Equations', 2),
    ('CS-3241',  'Operations Research',                     3)
) AS c(course_code, course_name, display_order)
CROSS JOIN LATERAL (
    SELECT unit_id FROM organizational_units
    WHERE unit_name = 'Faculty of Computing' OR unit_code = 'FC'
    LIMIT 1
) u
LEFT JOIN majors m ON m.major_code = CASE
    WHEN c.course_code LIKE 'CS-%' THEN 'CS'
    WHEN c.course_code LIKE 'CST-%' THEN 'CST'
    WHEN c.course_code LIKE 'CT-%' THEN 'CT'
    ELSE 'CST'
END
CROSS JOIN LATERAL (
    SELECT CAST(SUBSTRING(c.course_code FROM '[0-9]+') AS INTEGER) AS n
) d
JOIN semesters sem ON sem.semester_no = (d.n / 1000 - 1) * 2 + (d.n / 100 % 10)
ON CONFLICT (course_code) DO NOTHING;

-- ------------------------------------------------------------
-- 4f. DNL - Department of Natural Language (major: CST for shared/general courses)
-- ------------------------------------------------------------
INSERT INTO courses (unit_id, course_code, course_name, credit_unit, major_id, semester_id, is_required, display_order)
SELECT
    u.unit_id, c.course_code, c.course_name, 3, m.major_id, sem.semester_id, TRUE, c.display_order
FROM (VALUES
    ('M-1101', 'Myanmar Language',        1),
    ('E-1101', 'English Proficiency I',   2),
    ('E-2101', 'English Proficiency III', 3),
    ('E-4101', 'Business English',        4),
    ('M-1201', 'Myanmar Language',        1),
    ('E-1201', 'English Proficiency II',  2),
    ('E-2201', 'English Proficiency IV',  3)
) AS c(course_code, course_name, display_order)
CROSS JOIN LATERAL (
    SELECT unit_id FROM organizational_units
    WHERE unit_name = 'Department of Natural Language' OR unit_code = 'DNL'
    LIMIT 1
) u
LEFT JOIN majors m ON m.major_code = CASE
    WHEN c.course_code LIKE 'CS-%' THEN 'CS'
    WHEN c.course_code LIKE 'CST-%' THEN 'CST'
    WHEN c.course_code LIKE 'CT-%' THEN 'CT'
    ELSE 'CST'
END
CROSS JOIN LATERAL (
    SELECT CAST(SUBSTRING(c.course_code FROM '[0-9]+') AS INTEGER) AS n
) d
JOIN semesters sem ON sem.semester_no = (d.n / 1000 - 1) * 2 + (d.n / 100 % 10)
ON CONFLICT (course_code) DO NOTHING;

-- ------------------------------------------------------------
-- 4g. DNS - Department of Natural Science (major: CST for shared/general courses)
-- ------------------------------------------------------------
INSERT INTO courses (unit_id, course_code, course_name, credit_unit, major_id, semester_id, is_required, display_order)
SELECT
    u.unit_id, c.course_code, c.course_name, 3, m.major_id, sem.semester_id, TRUE, c.display_order
FROM (VALUES
    ('P-1101', 'College Physics', 1),
    ('P-1201', 'College Physics', 2)
) AS c(course_code, course_name, display_order)
CROSS JOIN LATERAL (
    SELECT unit_id FROM organizational_units
    WHERE unit_name = 'Department of Natural Science' OR unit_code = 'DNS'
    LIMIT 1
) u
LEFT JOIN majors m ON m.major_code = CASE
    WHEN c.course_code LIKE 'CS-%' THEN 'CS'
    WHEN c.course_code LIKE 'CST-%' THEN 'CST'
    WHEN c.course_code LIKE 'CT-%' THEN 'CT'
    ELSE 'CST'
END
CROSS JOIN LATERAL (
    SELECT CAST(SUBSTRING(c.course_code FROM '[0-9]+') AS INTEGER) AS n
) d
JOIN semesters sem ON sem.semester_no = (d.n / 1000 - 1) * 2 + (d.n / 100 % 10)
ON CONFLICT (course_code) DO NOTHING;

COMMIT;

-- ============================================================
-- VALIDATION QUERIES (run manually after seeding)
-- ============================================================
--
-- 1. Courses per semester with unit + major:
--    SELECT c.course_code, c.course_name, sem.semester_no AS semester,
--           u.unit_code, m.major_code
--    FROM courses c
--    JOIN semesters sem ON sem.semester_id = c.semester_id
--    JOIN organizational_units u ON u.unit_id = c.unit_id
--    LEFT JOIN majors m ON m.major_id = c.major_id
--    ORDER BY sem.semester_no, u.unit_code, c.display_order;
--
-- 2. Every course has a valid unit_id:
--    SELECT count(*) FROM courses c
--    LEFT JOIN organizational_units u ON u.unit_id = c.unit_id
--    WHERE u.unit_id IS NULL;
--
-- 3. Every course has a valid semester_id:
--    SELECT count(*) FROM courses c
--    LEFT JOIN semesters s ON s.semester_id = c.semester_id
--    WHERE s.semester_id IS NULL;
--
-- 4. No duplicate course_code:
--    SELECT course_code, count(*) FROM courses
--    GROUP BY course_code HAVING count(*) > 1;
--
-- 5. Idempotency check: re-running this script must insert 0 rows.
-- ============================================================
