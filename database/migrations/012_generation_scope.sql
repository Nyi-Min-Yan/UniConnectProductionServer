-- 012_generation_scope.sql
-- Persists the Mid/Final + semester/section scope chosen by the lobby creator
-- on the shared GenerationSession. Publish uses it to revalidate completeness
-- (period counts, session structure, section coverage) against the exact scope
-- that was generated, instead of trusting the original generation result.
--
-- TEXT is used (not jsonb) to keep the migration simple; the value is only
-- ever read/written through the application (Jackson-serialized JSON).
-- Idempotent: safe to re-run.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'generation_sessions'
                     AND column_name = 'scope_json') THEN
        ALTER TABLE generation_sessions ADD COLUMN scope_json TEXT;
    END IF;
END $$;
