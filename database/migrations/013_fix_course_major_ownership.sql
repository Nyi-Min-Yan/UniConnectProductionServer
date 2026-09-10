-- 013_fix_course_major_ownership.sql
-- Fix course major_id ownership according to authoritative prefix rules:
-- CS-*  -> CS
-- CST-* -> CST
-- CT-*  -> CT
-- Other (E-*, M-*, P-*, etc.) -> CST
-- Idempotent: safe to run more than once.

DO $$
DECLARE
    cs_major_id   UUID := '50494a0e-5f82-40c1-9cca-00b5c95c9366';
    cst_major_id  UUID := '7713bf90-0361-4a0e-9e4b-040925b60280';
    ct_major_id   UUID := 'c90f8f1a-47b4-4c77-a83a-029d1c09e6e0';
    updated_count INTEGER := 0;
BEGIN
    -- CS-* courses -> CS major
    UPDATE courses
    SET major_id = cs_major_id
    WHERE course_code LIKE 'CS-%'
      AND (major_id IS NULL OR major_id <> cs_major_id);
    GET DIAGNOSTICS updated_count = ROW_COUNT;
    RAISE NOTICE 'Updated % CS-* courses to CS major', updated_count;

    -- CST-* courses -> CST major
    UPDATE courses
    SET major_id = cst_major_id
    WHERE course_code LIKE 'CST-%'
      AND (major_id IS NULL OR major_id <> cst_major_id);
    GET DIAGNOSTICS updated_count = ROW_COUNT;
    RAISE NOTICE 'Updated % CST-* courses to CST major', updated_count;

    -- CT-* courses -> CT major
    UPDATE courses
    SET major_id = ct_major_id
    WHERE course_code LIKE 'CT-%'
      AND (major_id IS NULL OR major_id <> ct_major_id);
    GET DIAGNOSTICS updated_count = ROW_COUNT;
    RAISE NOTICE 'Updated % CT-* courses to CT major', updated_count;

    -- Other shared/general courses -> CST major
    UPDATE courses
    SET major_id = cst_major_id
    WHERE course_code NOT LIKE 'CS-%'
      AND course_code NOT LIKE 'CST-%'
      AND course_code NOT LIKE 'CT-%'
      AND (major_id IS NULL OR major_id <> cst_major_id);
    GET DIAGNOSTICS updated_count = ROW_COUNT;
    RAISE NOTICE 'Updated % other courses to CST major', updated_count;

    -- Verify no NULL major_id remains
    IF EXISTS (SELECT 1 FROM courses WHERE major_id IS NULL) THEN
        RAISE EXCEPTION 'Some courses still have NULL major_id after migration';
    END IF;

    -- Verify all major_id values are valid FK
    IF EXISTS (
        SELECT 1 FROM courses c
        WHERE c.major_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM majors m WHERE m.major_id = c.major_id)
    ) THEN
        RAISE EXCEPTION 'Some courses have invalid major_id FK after migration';
    END IF;

    RAISE NOTICE 'Course major_id ownership migration completed successfully';
END $$;