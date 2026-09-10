-- 014_enforce_course_major_not_null.sql
-- Enforce NOT NULL constraint on courses.major_id after all courses
-- have been assigned authoritative major ownership.
-- Idempotent: safe to run more than once.

DO $$
BEGIN
    -- Verify no NULL major_id remains
    IF EXISTS (SELECT 1 FROM courses WHERE major_id IS NULL) THEN
        RAISE EXCEPTION 'Cannot enforce NOT NULL: % courses still have NULL major_id',
            (SELECT count(*) FROM courses WHERE major_id IS NULL);
    END IF;

    -- Verify all major_id values are valid FK
    IF EXISTS (
        SELECT 1 FROM courses c
        WHERE c.major_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM majors m WHERE m.major_id = c.major_id)
    ) THEN
        RAISE EXCEPTION 'Cannot enforce NOT NULL: some courses have invalid major_id FK';
    END IF;

    -- Add NOT NULL constraint if not already present
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'courses'
          AND column_name = 'major_id'
          AND is_nullable = 'NO'
    ) THEN
        ALTER TABLE courses
            ALTER COLUMN major_id SET NOT NULL;
        RAISE NOTICE 'NOT NULL constraint added to courses.major_id';
    ELSE
        RAISE NOTICE 'courses.major_id already has NOT NULL constraint';
    END IF;
END $$;