-- ============================================================
-- Migration 019b: Semester 4 Corrections (Part 2-9)
-- ============================================================
-- Run this AFTER reviewing 019a output.
--
-- PREREQUISITES:
--   - The active term exists (2025-2026)
--   - Sections A, B, C, CT exist
--   - Semester 4 courses exist with teaching assignments
--   - Combined groups may or may not exist yet for Sem 4 courses
--
-- This script uses DO blocks with dynamic SQL to resolve IDs
-- at runtime (no hardcoded UUIDs).
-- ============================================================

BEGIN;

-- ============================================================
-- PART 2: CORRECT SEMESTER 4 COMBINED CLASS MEMBERSHIP
-- ============================================================
-- Target: CST-2241 → A + B + CT
--         E-2201   → A + B + CT
-- ============================================================

-- 2a. Fix CST-2241 combined group
DO $$
DECLARE
    v_term_id UUID;
    v_course_id UUID;
    v_group_id UUID;
    v_sec_a UUID;
    v_sec_b UUID;
    v_sec_ct UUID;
    v_asg_a UUID;
    v_asg_b UUID;
    v_asg_ct UUID;
    v_existing_member RECORD;
BEGIN
    -- Resolve active term
    SELECT term_id INTO v_term_id
    FROM academic_terms WHERE status = 'ACTIVE' LIMIT 1;

    IF v_term_id IS NULL THEN
        RAISE EXCEPTION 'No active academic term found';
    END IF;

    -- Resolve CST-2241 course
    SELECT course_id INTO v_course_id
    FROM courses WHERE course_code = 'CST-2241';

    IF v_course_id IS NULL THEN
        RAISE EXCEPTION 'Course CST-2241 not found';
    END IF;

    -- Resolve sections
    SELECT section_id INTO v_sec_a FROM sections WHERE section_name = 'A';
    SELECT section_id INTO v_sec_b FROM sections WHERE section_name = 'B';
    SELECT section_id INTO v_sec_ct FROM sections WHERE section_name = 'CT';

    IF v_sec_a IS NULL OR v_sec_b IS NULL OR v_sec_ct IS NULL THEN
        RAISE EXCEPTION 'Required sections (A, B, CT) not all found. A=%, B=%, CT=%',
            v_sec_a, v_sec_b, v_sec_ct;
    END IF;

    -- Resolve teaching assignments for CST-2241
    SELECT assignment_id INTO v_asg_a
    FROM teaching_assignments
    WHERE course_id = v_course_id AND section_id = v_sec_a
      AND term_id = v_term_id AND assignment_status != 'CANCELLED';

    SELECT assignment_id INTO v_asg_b
    FROM teaching_assignments
    WHERE course_id = v_course_id AND section_id = v_sec_b
      AND term_id = v_term_id AND assignment_status != 'CANCELLED';

    SELECT assignment_id INTO v_asg_ct
    FROM teaching_assignments
    WHERE course_id = v_course_id AND section_id = v_sec_ct
      AND term_id = v_term_id AND assignment_status != 'CANCELLED';

    RAISE NOTICE 'CST-2241: asg_a=%, asg_b=%, asg_ct=%', v_asg_a, v_asg_b, v_asg_ct;

    -- Find existing group for CST-2241
    SELECT group_id INTO v_group_id
    FROM teaching_assignment_groups
    WHERE term_id = v_term_id AND course_id = v_course_id;

    IF v_group_id IS NOT NULL THEN
        RAISE NOTICE 'CST-2241: Existing group found: %', v_group_id;

        -- Remove any members that are NOT A, B, or CT
        FOR v_existing_member IN
            SELECT tagm.assignment_id, sec.section_name
            FROM teaching_assignment_group_members tagm
            JOIN teaching_assignments ta ON ta.assignment_id = tagm.assignment_id
            JOIN sections sec ON sec.section_id = ta.section_id
            WHERE tagm.group_id = v_group_id
              AND sec.section_name NOT IN ('A', 'B', 'CT')
        LOOP
            RAISE NOTICE 'CST-2241: Removing incorrect member: % (section %)',
                v_existing_member.assignment_id, v_existing_member.section_name;
            DELETE FROM teaching_assignment_group_members
            WHERE group_id = v_group_id
              AND assignment_id = v_existing_member.assignment_id;
        END LOOP;

        -- Add missing members (A, B, CT)
        IF v_asg_a IS NOT NULL THEN
            INSERT INTO teaching_assignment_group_members (group_id, assignment_id)
            VALUES (v_group_id, v_asg_a)
            ON CONFLICT DO NOTHING;
        END IF;
        IF v_asg_b IS NOT NULL THEN
            INSERT INTO teaching_assignment_group_members (group_id, assignment_id)
            VALUES (v_group_id, v_asg_b)
            ON CONFLICT DO NOTHING;
        END IF;
        IF v_asg_ct IS NOT NULL THEN
            INSERT INTO teaching_assignment_group_members (group_id, assignment_id)
            VALUES (v_group_id, v_asg_ct)
            ON CONFLICT DO NOTHING;
        END IF;

        -- Update group name
        UPDATE teaching_assignment_groups
        SET group_name = 'CST-2241 (A + B + CT)'
        WHERE group_id = v_group_id;

    ELSE
        RAISE NOTICE 'CST-2241: No existing group found, creating new group';

        -- Create new group only if all 3 assignments exist
        IF v_asg_a IS NOT NULL AND v_asg_b IS NOT NULL AND v_asg_ct IS NOT NULL THEN
            INSERT INTO teaching_assignment_groups (group_id, term_id, course_id, group_name, created_at)
            VALUES (gen_random_uuid(), v_term_id, v_course_id, 'CST-2241 (A + B + CT)', now())
            RETURNING group_id INTO v_group_id;

            INSERT INTO teaching_assignment_group_members (group_id, assignment_id)
            VALUES (v_group_id, v_asg_a),
                   (v_group_id, v_asg_b),
                   (v_group_id, v_asg_ct);

            RAISE NOTICE 'CST-2241: Created new group % with members A, B, CT', v_group_id;
        ELSE
            RAISE WARNING 'CST-2241: Cannot create group - missing assignments. a=%, b=%, ct=%',
                v_asg_a, v_asg_b, v_asg_ct;
        END IF;
    END IF;
END $$;

-- 2b. Fix E-2201 combined group
DO $$
DECLARE
    v_term_id UUID;
    v_course_id UUID;
    v_group_id UUID;
    v_sec_a UUID;
    v_sec_b UUID;
    v_sec_ct UUID;
    v_asg_a UUID;
    v_asg_b UUID;
    v_asg_ct UUID;
    v_existing_member RECORD;
BEGIN
    -- Resolve active term
    SELECT term_id INTO v_term_id
    FROM academic_terms WHERE status = 'ACTIVE' LIMIT 1;

    -- Resolve E-2201 course
    SELECT course_id INTO v_course_id
    FROM courses WHERE course_code = 'E-2201';

    IF v_course_id IS NULL THEN
        RAISE EXCEPTION 'Course E-2201 not found';
    END IF;

    -- Resolve sections
    SELECT section_id INTO v_sec_a FROM sections WHERE section_name = 'A';
    SELECT section_id INTO v_sec_b FROM sections WHERE section_name = 'B';
    SELECT section_id INTO v_sec_ct FROM sections WHERE section_name = 'CT';

    -- Resolve teaching assignments for E-2201
    SELECT assignment_id INTO v_asg_a
    FROM teaching_assignments
    WHERE course_id = v_course_id AND section_id = v_sec_a
      AND term_id = v_term_id AND assignment_status != 'CANCELLED';

    SELECT assignment_id INTO v_asg_b
    FROM teaching_assignments
    WHERE course_id = v_course_id AND section_id = v_sec_b
      AND term_id = v_term_id AND assignment_status != 'CANCELLED';

    SELECT assignment_id INTO v_asg_ct
    FROM teaching_assignments
    WHERE course_id = v_course_id AND section_id = v_sec_ct
      AND term_id = v_term_id AND assignment_status != 'CANCELLED';

    RAISE NOTICE 'E-2201: asg_a=%, asg_b=%, asg_ct=%', v_asg_a, v_asg_b, v_asg_ct;

    -- Find existing group for E-2201
    SELECT group_id INTO v_group_id
    FROM teaching_assignment_groups
    WHERE term_id = v_term_id AND course_id = v_course_id;

    IF v_group_id IS NOT NULL THEN
        RAISE NOTICE 'E-2201: Existing group found: %', v_group_id;

        -- Remove any members that are NOT A, B, or CT
        FOR v_existing_member IN
            SELECT tagm.assignment_id, sec.section_name
            FROM teaching_assignment_group_members tagm
            JOIN teaching_assignments ta ON ta.assignment_id = tagm.assignment_id
            JOIN sections sec ON sec.section_id = ta.section_id
            WHERE tagm.group_id = v_group_id
              AND sec.section_name NOT IN ('A', 'B', 'CT')
        LOOP
            RAISE NOTICE 'E-2201: Removing incorrect member: % (section %)',
                v_existing_member.assignment_id, v_existing_member.section_name;
            DELETE FROM teaching_assignment_group_members
            WHERE group_id = v_group_id
              AND assignment_id = v_existing_member.assignment_id;
        END LOOP;

        -- Add missing members (A, B, CT)
        IF v_asg_a IS NOT NULL THEN
            INSERT INTO teaching_assignment_group_members (group_id, assignment_id)
            VALUES (v_group_id, v_asg_a)
            ON CONFLICT DO NOTHING;
        END IF;
        IF v_asg_b IS NOT NULL THEN
            INSERT INTO teaching_assignment_group_members (group_id, assignment_id)
            VALUES (v_group_id, v_asg_b)
            ON CONFLICT DO NOTHING;
        END IF;
        IF v_asg_ct IS NOT NULL THEN
            INSERT INTO teaching_assignment_group_members (group_id, assignment_id)
            VALUES (v_group_id, v_asg_ct)
            ON CONFLICT DO NOTHING;
        END IF;

        -- Update group name
        UPDATE teaching_assignment_groups
        SET group_name = 'E-2201 (A + B + CT)'
        WHERE group_id = v_group_id;

    ELSE
        RAISE NOTICE 'E-2201: No existing group found, creating new group';

        IF v_asg_a IS NOT NULL AND v_asg_b IS NOT NULL AND v_asg_ct IS NOT NULL THEN
            INSERT INTO teaching_assignment_groups (group_id, term_id, course_id, group_name, created_at)
            VALUES (gen_random_uuid(), v_term_id, v_course_id, 'E-2201 (A + B + CT)', now())
            RETURNING group_id INTO v_group_id;

            INSERT INTO teaching_assignment_group_members (group_id, assignment_id)
            VALUES (v_group_id, v_asg_a),
                   (v_group_id, v_asg_b),
                   (v_group_id, v_asg_ct);

            RAISE NOTICE 'E-2201: Created new group % with members A, B, CT', v_group_id;
        ELSE
            RAISE WARNING 'E-2201: Cannot create group - missing assignments. a=%, b=%, ct=%',
                v_asg_a, v_asg_b, v_asg_ct;
        END IF;
    END IF;
END $$;

-- ============================================================
-- PART 2 VERIFICATION
-- ============================================================
SELECT '=== SEMESTER 4 COMBINED GROUPS AFTER CORRECTION ===' AS section;
SELECT tag.group_id, tag.group_name, c.course_code, s.semester_no,
       STRING_AGG(sec.section_name, ' + ' ORDER BY sec.section_name) AS member_sections
FROM teaching_assignment_groups tag
JOIN courses c ON c.course_id = tag.course_id
JOIN semesters s ON s.semester_id = c.semester_id
JOIN teaching_assignment_group_members tagm ON tagm.group_id = tag.group_id
JOIN teaching_assignments ta ON ta.assignment_id = tagm.assignment_id
JOIN sections sec ON sec.section_id = ta.section_id
WHERE s.semester_no = 4
GROUP BY tag.group_id, tag.group_name, c.course_code, s.semester_no
ORDER BY c.course_code;

-- Verify Semester 1 groups are unchanged
SELECT '=== SEMESTER 1 COMBINED GROUPS (SHOULD BE UNCHANGED) ===' AS section;
SELECT tag.group_id, tag.group_name, c.course_code, s.semester_no,
       STRING_AGG(sec.section_name, ' + ' ORDER BY sec.section_name) AS member_sections
FROM teaching_assignment_groups tag
JOIN courses c ON c.course_id = tag.course_id
JOIN semesters s ON s.semester_id = c.semester_id
JOIN teaching_assignment_group_members tagm ON tagm.group_id = tag.group_id
JOIN teaching_assignments ta ON ta.assignment_id = tagm.assignment_id
JOIN sections sec ON sec.section_id = ta.section_id
WHERE s.semester_no = 1
GROUP BY tag.group_id, tag.group_name, c.course_code, s.semester_no
ORDER BY c.course_code;

-- ============================================================
-- PART 3-4: LECTURER CONFLICT ANALYSIS
-- ============================================================

-- 3a. Semester 4 staff assignments with course details
SELECT '=== SEMESTER 4 STAFF CONFLICT ANALYSIS ===' AS section;
SELECT st.staff_no, st.staff_name, u.unit_code AS dept,
       c.course_code, sec.section_name,
       ta.assignment_id, ta.assignment_status
FROM teaching_assignments ta
JOIN courses c ON c.course_id = ta.course_id
JOIN semesters s ON s.semester_id = c.semester_id
JOIN sections sec ON sec.section_id = ta.section_id
JOIN staff st ON st.staff_id = ta.staff_id
LEFT JOIN organizational_units u ON u.unit_id = st.unit_id
WHERE s.semester_no = 4
  AND ta.assignment_status != 'CANCELLED'
ORDER BY st.staff_no, c.course_code, sec.section_name;

-- 3b. Staff shared across multiple Semester 4 courses (real conflict)
SELECT '=== STAFF SHARED ACROSS MULTIPLE SEMESTER 4 COURSES ===' AS section;
SELECT st.staff_no, st.staff_name, u.unit_code AS dept,
       COUNT(DISTINCT c.course_id) AS num_courses,
       STRING_AGG(DISTINCT c.course_code, ', ' ORDER BY c.course_code) AS courses,
       STRING_AGG(DISTINCT sec.section_name, ', ' ORDER BY sec.section_name) AS sections
FROM teaching_assignments ta
JOIN courses c ON c.course_id = ta.course_id
JOIN semesters s ON s.semester_id = c.semester_id
JOIN sections sec ON sec.section_id = ta.section_id
JOIN staff st ON st.staff_id = ta.staff_id
LEFT JOIN organizational_units u ON u.unit_id = st.unit_id
WHERE s.semester_no = 4
  AND ta.assignment_status != 'CANCELLED'
GROUP BY st.staff_no, st.staff_name, u.unit_code
HAVING COUNT(DISTINCT c.course_id) > 1
ORDER BY st.staff_no;

-- 3c. Cross-semester assignments for Semester 4 staff
SELECT '=== CROSS-SEMESTER ASSIGNMENTS FOR SEM4 STAFF ===' AS section;
SELECT st.staff_no, st.staff_name, s.semester_no,
       STRING_AGG(DISTINCT c.course_code, ', ' ORDER BY c.course_code) AS courses,
       COUNT(DISTINCT c.course_id) AS num_courses
FROM teaching_assignments ta
JOIN courses c ON c.course_id = ta.course_id
JOIN semesters s ON s.semester_id = c.semester_id
JOIN staff st ON st.staff_id = ta.staff_id
WHERE ta.assignment_status != 'CANCELLED'
  AND st.staff_id IN (
      SELECT DISTINCT ta2.staff_id
      FROM teaching_assignments ta2
      JOIN courses c2 ON c2.course_id = ta2.course_id
      JOIN semesters s2 ON s2.semester_id = c2.semester_id
      WHERE s2.semester_no = 4 AND ta2.assignment_status != 'CANCELLED'
  )
GROUP BY st.staff_no, st.staff_name, s.semester_no
ORDER BY st.staff_no, s.semester_no;

-- ============================================================
-- PART 5: CREATE ADDITIONAL LECTURER IF NEEDED
-- ============================================================
-- This section only runs if the analysis above shows a real
-- same-semester conflict (staff shared across 2+ distinct courses).
-- ============================================================

DO $$
DECLARE
    v_conflict_staff RECORD;
    v_staff_role_id UUID;
    v_lecturer_pos_id UUID;
    v_new_user_id UUID;
    v_new_staff_id UUID;
    v_new_staff_no TEXT;
    v_counter INT := 1;
    v_course_to_reassign RECORD;
    v_nl_unit_id UUID;
    v_fcs_unit_id UUID;
    v_fc_unit_id UUID;
    v_itsm_unit_id UUID;
    v_fis_unit_id UUID;
    v_fcst_unit_id UUID;
    v_conflict_count INT := 0;
BEGIN
    -- Get role and position IDs
    SELECT role_id INTO v_staff_role_id FROM roles WHERE role_name = 'STAFF';
    SELECT position_id INTO v_lecturer_pos_id FROM positions WHERE position_name = 'LECTURER';

    -- Get unit IDs for departments
    SELECT unit_id INTO v_nl_unit_id FROM organizational_units WHERE unit_code = 'NL' OR unit_code = 'DNL' LIMIT 1;
    SELECT unit_id INTO v_fcs_unit_id FROM organizational_units WHERE unit_code = 'FCS' LIMIT 1;
    SELECT unit_id INTO v_fc_unit_id FROM organizational_units WHERE unit_code = 'FC' LIMIT 1;
    SELECT unit_id INTO v_itsm_unit_id FROM organizational_units WHERE unit_code = 'ITSM' LIMIT 1;
    SELECT unit_id INTO v_fis_unit_id FROM organizational_units WHERE unit_code = 'FIS' LIMIT 1;
    SELECT unit_id INTO v_fcst_unit_id FROM organizational_units WHERE unit_code = 'FCST' LIMIT 1;

    -- Find staff teaching 2+ different Semester 4 courses
    FOR v_conflict_staff IN
        SELECT st.staff_id, st.staff_no, st.staff_name, st.unit_id,
               ARRAY_AGG(DISTINCT c.course_id) AS course_ids,
               ARRAY_AGG(DISTINCT c.course_code) AS course_codes,
               COUNT(DISTINCT c.course_id) AS num_courses
        FROM teaching_assignments ta
        JOIN courses c ON c.course_id = ta.course_id
        JOIN semesters s ON s.semester_id = c.semester_id
        JOIN staff st ON st.staff_id = ta.staff_id
        WHERE s.semester_no = 4
          AND ta.assignment_status != 'CANCELLED'
        GROUP BY st.staff_id, st.staff_no, st.staff_name, st.unit_id
        HAVING COUNT(DISTINCT c.course_id) > 1
    LOOP
        v_conflict_count := v_conflict_count + 1;
        RAISE NOTICE 'CONFLICT DETECTED: % (%) teaches % courses: %',
            v_conflict_staff.staff_name, v_conflict_staff.staff_no,
            v_conflict_staff.num_courses, v_conflict_staff.course_codes;

        -- For each conflict, we reassign the SECOND course to a new lecturer
        -- Pick the course that appears LAST alphabetically for reassignment
        FOR v_course_to_reassign IN
            SELECT DISTINCT c.course_id, c.course_code, c.unit_id,
                   ta.assignment_id, sec.section_name
            FROM teaching_assignments ta
            JOIN courses c ON c.course_id = ta.course_id
            JOIN semesters s ON s.semester_id = c.semester_id
            JOIN sections sec ON sec.section_id = ta.section_id
            WHERE ta.staff_id = v_conflict_staff.staff_id
              AND s.semester_no = 4
              AND ta.assignment_status != 'CANCELLED'
              AND c.course_code = (
                  SELECT MAX(c2.course_code)
                  FROM teaching_assignments ta2
                  JOIN courses c2 ON c2.course_id = ta2.course_id
                  JOIN semesters s2 ON s2.semester_id = c2.semester_id
                  WHERE ta2.staff_id = v_conflict_staff.staff_id
                    AND s2.semester_no = 4
                    AND ta2.assignment_status != 'CANCELLED'
              )
            ORDER BY sec.section_name
        LOOP
            -- Create new lecturer for this course
            v_new_staff_no := 'STF0' || (30 + v_counter)::TEXT;

            -- Check if this staff number already exists
            IF EXISTS (SELECT 1 FROM staff WHERE staff_no = v_new_staff_no) THEN
                v_new_staff_no := 'STF0' || (40 + v_counter)::TEXT;
            END IF;

            -- Create user account
            INSERT INTO users (user_id, email, password_hash, role_id, is_active, registration_status, created_at, updated_at)
            VALUES (gen_random_uuid(),
                    LOWER(REPLACE(v_new_staff_no, ' ', '')) || '@unicconnect.com',
                    '$2a$10$dummy_hash_for_seeded_lecturer',
                    v_staff_role_id,
                    TRUE,
                    'APPROVED',
                    now(), now())
            RETURNING user_id INTO v_new_user_id;

            -- Create staff record under the SAME department as the course
            INSERT INTO staff (staff_id, user_id, staff_no, staff_name, phone_no, unit_id, joined_at, created_at, updated_at)
            VALUES (gen_random_uuid(),
                    v_new_user_id,
                    v_new_staff_no,
                    'Sem4 ' || v_course_to_reassign.course_code || ' Lecturer',
                    '09-0000' || LPAD(v_counter::TEXT, 4, '0'),
                    v_course_to_reassign.unit_id,
                    CURRENT_DATE,
                    now(), now())
            RETURNING staff_id INTO v_new_staff_id;

            -- Assign LECTURER position
            INSERT INTO staff_position_assignments (position_assignment_id, staff_id, position_id, start_date)
            VALUES (gen_random_uuid(), v_new_staff_id, v_lecturer_pos_id, CURRENT_DATE);

            -- Reassign the teaching assignment to the new lecturer
            UPDATE teaching_assignments
            SET staff_id = v_new_staff_id
            WHERE assignment_id = v_course_to_reassign.assignment_id;

            RAISE NOTICE 'REASSIGNED: % section % from % to new lecturer % (%)',
                v_course_to_reassign.course_code, v_course_to_reassign.section_name,
                v_conflict_staff.staff_name,
                'Sem4 ' || v_course_to_reassign.course_code || ' Lecturer',
                v_new_staff_no;

            v_counter := v_counter + 1;
        END LOOP;
    END LOOP;

    IF v_conflict_count = 0 THEN
        RAISE NOTICE 'No same-semester lecturer conflicts found for Semester 4';
    ELSE
        RAISE NOTICE 'Resolved % lecturer conflict(s)', v_conflict_count;
    END IF;
END $$;

-- ============================================================
-- PART 9: POST-UPDATE VERIFICATION
-- ============================================================

-- 9a. Verify Semester 4 combined groups
SELECT '=== AFTER: SEMESTER 4 COMBINED GROUPS ===' AS section;
SELECT tag.group_id, tag.group_name, c.course_code, s.semester_no,
       STRING_AGG(sec.section_name, ' + ' ORDER BY sec.section_name) AS member_sections,
       STRING_AGG(DISTINCT st.staff_name, ', ' ORDER BY st.staff_name) AS lecturers
FROM teaching_assignment_groups tag
JOIN courses c ON c.course_id = tag.course_id
JOIN semesters s ON s.semester_id = c.semester_id
JOIN teaching_assignment_group_members tagm ON tagm.group_id = tag.group_id
JOIN teaching_assignments ta ON ta.assignment_id = tagm.assignment_id
JOIN sections sec ON sec.section_id = ta.section_id
JOIN staff st ON st.staff_id = ta.staff_id
WHERE s.semester_no = 4
GROUP BY tag.group_id, tag.group_name, c.course_code, s.semester_no
ORDER BY c.course_code;

-- 9b. Verify Semester 1 groups unchanged
SELECT '=== AFTER: SEMESTER 1 COMBINED GROUPS (UNCHANGED) ===' AS section;
SELECT tag.group_id, tag.group_name, c.course_code, s.semester_no,
       STRING_AGG(sec.section_name, ' + ' ORDER BY sec.section_name) AS member_sections
FROM teaching_assignment_groups tag
JOIN courses c ON c.course_id = tag.course_id
JOIN semesters s ON s.semester_id = c.semester_id
JOIN teaching_assignment_group_members tagm ON tagm.group_id = tag.group_id
JOIN teaching_assignments ta ON ta.assignment_id = tagm.assignment_id
JOIN sections sec ON sec.section_id = ta.section_id
WHERE s.semester_no = 1
GROUP BY tag.group_id, tag.group_name, c.course_code, s.semester_no
ORDER BY c.course_code;

-- 9c. Verify staff assignments after changes
SELECT '=== AFTER: SEMESTER 4 STAFF ASSIGNMENTS ===' AS section;
SELECT st.staff_no, st.staff_name, u.unit_code AS dept,
       STRING_AGG(DISTINCT c.course_code || ' (' || sec.section_name || ')',
                  ', ' ORDER BY c.course_code || ' (' || sec.section_name || ')') AS assignments
FROM teaching_assignments ta
JOIN courses c ON c.course_id = ta.course_id
JOIN semesters s ON s.semester_id = c.semester_id
JOIN sections sec ON sec.section_id = ta.section_id
JOIN staff st ON st.staff_id = ta.staff_id
LEFT JOIN organizational_units u ON u.unit_id = st.unit_id
WHERE s.semester_no = 4
  AND ta.assignment_status != 'CANCELLED'
GROUP BY st.staff_no, st.staff_name, u.unit_code
ORDER BY st.staff_no;

-- 9d. Final conflict check
SELECT '=== AFTER: REMAINING SEMESTER 4 CONFLICTS ===' AS section;
SELECT st.staff_no, st.staff_name, u.unit_code AS dept,
       COUNT(DISTINCT c.course_id) AS num_courses,
       STRING_AGG(DISTINCT c.course_code, ', ' ORDER BY c.course_code) AS courses
FROM teaching_assignments ta
JOIN courses c ON c.course_id = ta.course_id
JOIN semesters s ON s.semester_id = c.semester_id
JOIN staff st ON st.staff_id = ta.staff_id
LEFT JOIN organizational_units u ON u.unit_id = st.unit_id
WHERE s.semester_no = 4
  AND ta.assignment_status != 'CANCELLED'
GROUP BY st.staff_no, st.staff_name, u.unit_code
HAVING COUNT(DISTINCT c.course_id) > 1
ORDER BY st.staff_no;

-- 9e. New lecturers created
SELECT '=== NEW LECTURERS CREATED ===' AS section;
SELECT st.staff_no, st.staff_name, u.unit_code AS dept,
       st.staff_id, st.created_at
FROM staff st
LEFT JOIN organizational_units u ON u.unit_id = st.unit_id
WHERE st.staff_no LIKE 'STF03%' OR st.staff_no LIKE 'STF04%'
ORDER BY st.staff_no;

-- ============================================================
-- COMMIT
-- ============================================================
COMMIT;

-- ============================================================
-- FINAL SUMMARY
-- ============================================================
SELECT '=== MIGRATION 019 COMPLETE ===' AS status;
SELECT 'Combined class correction: CST-2241 and E-2201 should now be A+B+CT' AS result;
SELECT 'Lecturer conflicts resolved where applicable' AS result;
SELECT 'Semester 1 groups verified unchanged' AS result;
