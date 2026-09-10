-- Stores why an async timetable generation failed so the UI can show the
-- exact reason (e.g. capacity overload naming the semester, section and the
-- courses that exceed the weekly grid) instead of a bare FAILED status.
ALTER TABLE generation_sessions ADD COLUMN IF NOT EXISTS failure_report TEXT;
