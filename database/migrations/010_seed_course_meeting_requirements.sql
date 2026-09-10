-- 010_seed_course_meeting_requirements.sql
-- Idempotent initialization/update of COURSE_MEETING_REQUIREMENTS for every
-- course in the curriculum (FCS, FCST, FIS, ITSM, FC, Natural Language,
-- Natural Science).
--
-- Required configuration for every course: 4 periods/week = 2 sessions x 2
-- periods (sessions_per_week = 2, periods_per_session = 2).
--
-- meeting_type follows existing data:
--   * Existing requirements keep their current LECTURE/LAB type.
--   * Newly inserted requirements default to LECTURE (no existing business
--     data identifies those courses as LAB).
-- Only CST-2135, CST-3136 and CT-2236 carry an existing LAB requirement and
-- that type is preserved.
--
-- Safe to run repeatedly: never creates duplicates (guarded by NOT EXISTS /
-- ON CONFLICT DO NOTHING) and only touches rows whose values differ.
-- Applied by the migration runner inside a single transaction.
--
-- Step 1: insert missing requirements (2 x 2 = 4 periods/week).
INSERT INTO course_meeting_requirements (course_id, meeting_type, sessions_per_week, periods_per_session)
SELECT c.course_id, 'LECTURE', 2, 2
FROM courses c
WHERE c.course_code IN (
    'CST-1102','CST-2112','CST-2113','CST-3112','CST-3113','CST-4112','CS-4115','CST-4137',
    'CST-1212','CST-2212','CST-2213','CS-3212','CS-3215','CST-3217',
    'CST-2135','CST-3136','CT-3134','CT-3135','CT-3137','CT-4131','CT-4134','CT-4125','CT-4136','CT-4137',
    'CST-1234','CST-2235','CT-2234','CT-2236','CT-3231','CT-3232','CT-3233','CT-3235',
    'CST-1123','CST-2123','CST-2126','CS-3124','CS-3125','CST-4123','CS-4124','CS-4126',
    'CST-1223','CST-2224','CS-3223','CST-3226',
    'CST-1154','CS-3117','CS-3157A','CS-3157B','CST-4158','CS-2256','CST-3254','CST-3258',
    'CST-1141','CST-2141','CST-3141','CST-4141','CST-1241','CST-2241','CS-3241',
    'M-1101','E-1101','E-2101','E-4101','M-1201','E-1201','E-2201',
    'P-1101','P-1201'
)
AND NOT EXISTS (
    SELECT 1 FROM course_meeting_requirements r WHERE r.course_id = c.course_id
)
ON CONFLICT DO NOTHING;

-- Step 2: correct any existing requirement to 2 x 2 (meeting_type preserved).
UPDATE course_meeting_requirements r
SET sessions_per_week = 2, periods_per_session = 2
FROM courses c
WHERE c.course_id = r.course_id
  AND c.course_code IN (
    'CST-1102','CST-2112','CST-2113','CST-3112','CST-3113','CST-4112','CS-4115','CST-4137',
    'CST-1212','CST-2212','CST-2213','CS-3212','CS-3215','CST-3217',
    'CST-2135','CST-3136','CT-3134','CT-3135','CT-3137','CT-4131','CT-4134','CT-4125','CT-4136','CT-4137',
    'CST-1234','CST-2235','CT-2234','CT-2236','CT-3231','CT-3232','CT-3233','CT-3235',
    'CST-1123','CST-2123','CST-2126','CS-3124','CS-3125','CST-4123','CS-4124','CS-4126',
    'CST-1223','CST-2224','CS-3223','CST-3226',
    'CST-1154','CS-3117','CS-3157A','CS-3157B','CST-4158','CS-2256','CST-3254','CST-3258',
    'CST-1141','CST-2141','CST-3141','CST-4141','CST-1241','CST-2241','CS-3241',
    'M-1101','E-1101','E-2101','E-4101','M-1201','E-1201','E-2201',
    'P-1101','P-1201'
  )
  AND (r.sessions_per_week <> 2 OR r.periods_per_session <> 2);
