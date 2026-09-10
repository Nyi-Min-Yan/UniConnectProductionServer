-- 017_attendance_actual_period_range.sql
-- ATTENDANCE stores the student's ACTUAL attended period range directly:
--   attendance_start_slot_id / attendance_end_slot_id -> time_slots(slot_id)
-- Replaces the per-period rows design (attendance_periods) with a simple,
-- contiguous range. Attended periods are ALWAYS derived dynamically as
--   end_slot.display_order - start_slot.display_order + 1
-- and are never stored.

BEGIN;

ALTER TABLE attendance
    ADD COLUMN IF NOT EXISTS attendance_start_slot_id UUID REFERENCES time_slots(slot_id),
    ADD COLUMN IF NOT EXISTS attendance_end_slot_id   UUID REFERENCES time_slots(slot_id);

CREATE INDEX IF NOT EXISTS idx_attendance_start_slot ON attendance(attendance_start_slot_id);
CREATE INDEX IF NOT EXISTS idx_attendance_end_slot   ON attendance(attendance_end_slot_id);

-- Backfill any legacy per-period rows into the range representation before
-- dropping the old table (no-op when attendance_periods is empty/absent).
DO $$
DECLARE
    has_legacy BOOLEAN := EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'attendance_periods');
BEGIN
    IF has_legacy THEN
        UPDATE attendance a
           SET attendance_start_slot_id = agg.min_slot,
               attendance_end_slot_id   = agg.max_slot
          FROM (
              SELECT ap.attendance_id,
                     (SELECT ap2.slot_id FROM attendance_periods ap2
                       JOIN time_slots ts2 ON ts2.slot_id = ap2.slot_id
                       WHERE ap2.attendance_id = ap.attendance_id
                       ORDER BY ts2.display_order ASC LIMIT 1) AS min_slot,
                     (SELECT ap3.slot_id FROM attendance_periods ap3
                       JOIN time_slots ts3 ON ts3.slot_id = ap3.slot_id
                       WHERE ap3.attendance_id = ap.attendance_id
                       ORDER BY ts3.display_order DESC LIMIT 1) AS max_slot
                FROM attendance_periods ap
               GROUP BY ap.attendance_id
          ) AS agg
         WHERE a.attendance_id = agg.attendance_id;
        DROP TABLE attendance_periods;
    END IF;
END $$;

-- Status/range coherence:
--   PRESENT => both range ids required.
--   ABSENT  => both must be NULL.
ALTER TABLE attendance DROP CONSTRAINT IF EXISTS ck_attendance_present_requires_range;
ALTER TABLE attendance ADD CONSTRAINT ck_attendance_present_requires_range CHECK (
    attendance_status <> 'PRESENT'
    OR (attendance_start_slot_id IS NOT NULL AND attendance_end_slot_id IS NOT NULL));

ALTER TABLE attendance DROP CONSTRAINT IF EXISTS ck_attendance_absent_has_no_range;
ALTER TABLE attendance ADD CONSTRAINT ck_attendance_absent_has_no_range CHECK (
    attendance_status <> 'ABSENT'
    OR (attendance_start_slot_id IS NULL AND attendance_end_slot_id IS NULL));

COMMIT;
