-- Roll Call / Attendance: actual attended periods per student per session.
-- One ATTENDANCE row = the student's presence decision for the session.
-- ATTENDANCE_PERIODS = the individual timetable slots the student received
-- attendance credit for (a 2-period class can credit 1 or 2 slots).
-- Derived percentages are NEVER stored; they are calculated dynamically.

CREATE TABLE IF NOT EXISTS attendance_periods (
    attendance_period_id UUID PRIMARY KEY,
    attendance_id        UUID NOT NULL REFERENCES attendance(attendance_id) ON DELETE CASCADE,
    slot_id              UUID NOT NULL REFERENCES time_slots(slot_id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_attendance_periods UNIQUE (attendance_id, slot_id)
);

CREATE INDEX IF NOT EXISTS idx_attendance_periods_attendance ON attendance_periods(attendance_id);
CREATE INDEX IF NOT EXISTS idx_attendance_periods_slot ON attendance_periods(slot_id);

-- One attendance decision per (session, student).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_attendance_session_student'
    ) THEN
        ALTER TABLE attendance
            ADD CONSTRAINT uq_attendance_session_student UNIQUE (session_id, student_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_class_sessions_schedule_date ON class_sessions(schedule_id, session_date);
CREATE INDEX IF NOT EXISTS idx_attendance_student ON attendance(student_id);
