-- 009_fix_time_slots_to_six_periods.sql
-- Corrects the timetable day to exactly 6 course periods, 09:00-16:00:
--   P1 09:00-10:00, P2 10:00-11:00, P3 11:00-12:00, LUNCH 12:00-13:00,
--   P4 13:00-14:00, P5 14:00-15:00, P6 15:00-16:00.
-- The previous seed created 8 hourly slots starting at 08:00 and ending at
-- 17:00. The two trailing slots (15:00-16:00 as period 7 and 16:00-17:00 as
-- period 8) are removed and any class_schedules referencing them are folded
-- into the new period 6 slot. Existing slot IDs 1-6 are preserved.

BEGIN;

-- 1. Reassign schedules that referenced the removed trailing slots to the
--    new period 6 slot (md5('slot-6')::uuid = f408cb3b-...).
UPDATE class_schedules
   SET start_slot_id = md5('slot-6')::uuid
 WHERE start_slot_id IN (md5('slot-7')::uuid, md5('slot-8')::uuid);

UPDATE class_schedules
   SET end_slot_id = md5('slot-6')::uuid
 WHERE end_slot_id IN (md5('slot-7')::uuid, md5('slot-8')::uuid);

-- 2. Correct periods and times for the six kept slots (IDs preserved).
UPDATE time_slots SET period_no = 1, start_time = '09:00:00', end_time = '10:00:00', display_order = 1 WHERE slot_id = md5('slot-1')::uuid;
UPDATE time_slots SET period_no = 2, start_time = '10:00:00', end_time = '11:00:00', display_order = 2 WHERE slot_id = md5('slot-2')::uuid;
UPDATE time_slots SET period_no = 3, start_time = '11:00:00', end_time = '12:00:00', display_order = 3 WHERE slot_id = md5('slot-3')::uuid;
UPDATE time_slots SET period_no = 4, start_time = '13:00:00', end_time = '14:00:00', display_order = 4 WHERE slot_id = md5('slot-4')::uuid;
UPDATE time_slots SET period_no = 5, start_time = '14:00:00', end_time = '15:00:00', display_order = 5 WHERE slot_id = md5('slot-5')::uuid;
UPDATE time_slots SET period_no = 6, start_time = '15:00:00', end_time = '16:00:00', display_order = 6 WHERE slot_id = md5('slot-6')::uuid;

-- 3. Remove the two trailing slots (16:00-17:00 end is no longer valid).
DELETE FROM time_slots WHERE slot_id IN (md5('slot-7')::uuid, md5('slot-8')::uuid);

COMMIT;
