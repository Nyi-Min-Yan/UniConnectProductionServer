-- 018_attendance_range_and_session_uniqueness.sql
-- Hardens the Roll Call data model at the DATABASE level:
--   1. CLASS_SESSIONS uniqueness per (schedule_id, session_date):
--      clicking Roll Call twice must reuse, never duplicate, today's session.
--   2. ATTENDANCE actual-range integrity via trigger (a CHECK cannot
--      subquery time_slots/class_schedules):
--        - PRESENT start.display_order <= end.display_order
--        - both orders inside the owning schedule's [start..end] span
--      The service layer validates identically; the database no longer
--      trusts it either.

BEGIN;

-- ---------- 1. unique class session per (schedule, date) ----------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'class_sessions'::regclass
          AND conname = 'uq_class_sessions_schedule_date'
    ) THEN
        -- table may contain legacy duplicates in other environments;
        -- keep one deterministic row per (schedule, date) before constraining.
        DELETE FROM class_sessions a
         USING class_sessions b
         WHERE a.schedule_id = b.schedule_id
           AND a.session_date = b.session_date
           AND a.ctid < b.ctid;
        ALTER TABLE class_sessions
            ADD CONSTRAINT uq_class_sessions_schedule_date
            UNIQUE (schedule_id, session_date);
    END IF;
END $$;

-- ---------- 2. attendance range trigger ----------
CREATE OR REPLACE FUNCTION fn_validate_attendance_range() RETURNS trigger AS $$
DECLARE
    v_start_ord INT;
    v_end_ord   INT;
    v_sched_lo  INT;
    v_sched_hi  INT;
BEGIN
    IF NEW.attendance_status = 'PRESENT' THEN
        SELECT ts.display_order INTO v_start_ord
          FROM time_slots ts WHERE ts.slot_id = NEW.attendance_start_slot_id;
        SELECT ts.display_order INTO v_end_ord
          FROM time_slots ts WHERE ts.slot_id = NEW.attendance_end_slot_id;

        IF v_start_ord IS NULL OR v_end_ord IS NULL THEN
            RAISE EXCEPTION 'attendance range slots must exist';
        END IF;

        SELECT LEAST(cs.start_slot_id, cs.end_slot_id),
               GREATEST(cs.start_slot_id, cs.end_slot_id)
          INTO v_sched_lo, v_sched_hi
          FROM (
              SELECT s1.display_order AS start_slot_id, s2.display_order AS end_slot_id
                FROM class_sessions sess
                JOIN class_schedules sch ON sch.schedule_id = sess.schedule_id
                JOIN time_slots s1 ON s1.slot_id = sch.start_slot_id
                JOIN time_slots s2 ON s2.slot_id = sch.end_slot_id
               WHERE sess.session_id = NEW.session_id
          ) cs;

        IF v_start_ord > v_end_ord THEN
            RAISE EXCEPTION 'attendance start slot cannot be after end slot';
        END IF;
        IF v_start_ord < v_sched_lo OR v_end_ord > v_sched_hi THEN
            RAISE EXCEPTION 'attendance range outside the scheduled period range';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_attendance_range ON attendance;
CREATE TRIGGER trg_attendance_range
    BEFORE INSERT OR UPDATE ON attendance
    FOR EACH ROW EXECUTE FUNCTION fn_validate_attendance_range();

COMMIT;
