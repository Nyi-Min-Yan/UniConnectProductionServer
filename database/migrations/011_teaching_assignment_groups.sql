-- 011_teaching_assignment_groups.sql
-- Shared teaching (combined-section) timetable support.
--
-- A teaching assignment group bundles multiple section assignments of the SAME
-- course in the SAME term into one timetable unit (e.g. "CS-2256 A + B + C"),
-- so the generator places a single class_schedules row for all member sections
-- and every member section / member lecturer participates in conflict checks.
--
-- Idempotent: safe to run more than once.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_name = 'teaching_assignment_groups') THEN
        CREATE TABLE teaching_assignment_groups (
            group_id    UUID PRIMARY KEY,
            term_id     UUID NOT NULL REFERENCES academic_terms(term_id)
                               ON UPDATE CASCADE ON DELETE RESTRICT,
            course_id   UUID NOT NULL REFERENCES courses(course_id)
                               ON UPDATE CASCADE ON DELETE RESTRICT,
            group_name  VARCHAR(120) NOT NULL,
            created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        CREATE INDEX idx_teaching_assignment_groups_term ON teaching_assignment_groups(term_id);
        CREATE INDEX idx_teaching_assignment_groups_course ON teaching_assignment_groups(course_id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_name = 'teaching_assignment_group_members') THEN
        CREATE TABLE teaching_assignment_group_members (
            group_id      UUID NOT NULL REFERENCES teaching_assignment_groups(group_id)
                                 ON UPDATE CASCADE ON DELETE CASCADE,
            assignment_id UUID NOT NULL REFERENCES teaching_assignments(assignment_id)
                                 ON UPDATE CASCADE ON DELETE CASCADE,
            PRIMARY KEY (group_id, assignment_id),
            CONSTRAINT uq_group_member_assignment UNIQUE (assignment_id)
        );
        CREATE INDEX idx_group_members_group ON teaching_assignment_group_members(group_id);
        CREATE INDEX idx_group_members_assignment ON teaching_assignment_group_members(assignment_id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'class_schedules'
                     AND column_name = 'teaching_group_id') THEN
        ALTER TABLE class_schedules
            ADD COLUMN teaching_group_id UUID
                REFERENCES teaching_assignment_groups(group_id)
                    ON UPDATE CASCADE ON DELETE RESTRICT;
        CREATE INDEX idx_class_schedules_teaching_group ON class_schedules(teaching_group_id);
    END IF;
END $$;

-- Relax the COURSE CHECK: a COURSE schedule must reference exactly one of
-- teaching_assignment_id (single-section) or teaching_group_id (combined),
-- never both; non-course specials keep both null. BREAK remains allowed by
-- the schema type check but is never inserted by the generator.
ALTER TABLE class_schedules DROP CONSTRAINT IF EXISTS chk_class_schedules_teaching_assignment;
ALTER TABLE class_schedules ADD CONSTRAINT chk_class_schedules_teaching_assignment CHECK (
    (schedule_type::text = 'COURSE'::text
        AND teaching_assignment_id IS NOT NULL
        AND teaching_group_id IS NULL)
    OR
    (schedule_type::text = 'COURSE'::text
        AND teaching_assignment_id IS NULL
        AND teaching_group_id IS NOT NULL)
    OR
    (schedule_type::text = ANY (ARRAY['LMS'::character varying,
                                       'ASSIGNMENT'::character varying,
                                       'BREAK'::character varying]::text[])
        AND teaching_assignment_id IS NULL
        AND teaching_group_id IS NULL)
);
