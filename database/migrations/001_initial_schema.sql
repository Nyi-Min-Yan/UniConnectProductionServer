-- ============================================================
-- UniConnect - University Management System
-- Initial schema migration
-- Target: Neon PostgreSQL
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- AUTHENTICATION
-- ============================================================

-- Roles: lookup of user roles (STUDENT, TEACHER, SYSTEM_ADMIN, ...)
CREATE TABLE roles (
    role_id     UUID DEFAULT gen_random_uuid(),
    role_name   VARCHAR(50)  NOT NULL,
    description TEXT,
    CONSTRAINT pk_roles PRIMARY KEY (role_id),
    CONSTRAINT uq_roles_role_name UNIQUE (role_name)
);

CREATE TABLE users (
    user_id               UUID DEFAULT gen_random_uuid(),
    email                 VARCHAR(255) NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    role_id               UUID NOT NULL,
    is_active             BOOLEAN NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    account_locked_until  TIMESTAMPTZ,
    registration_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    last_login            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_users PRIMARY KEY (user_id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT fk_users_role FOREIGN KEY (role_id)
        REFERENCES roles (role_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT chk_users_registration_status
        CHECK (registration_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_users_failed_login_attempts
        CHECK (failed_login_attempts >= 0)
);

CREATE TABLE refresh_tokens (
    token_id    UUID DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL,
    token_hash  VARCHAR(255) NOT NULL,
    device_name VARCHAR(255),
    ip_address  VARCHAR(45),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (token_id),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id)
        REFERENCES users (user_id)
        ON UPDATE CASCADE ON DELETE CASCADE
);

-- ============================================================
-- ORGANIZATION
-- ============================================================

CREATE TABLE organizational_units (
    unit_id     UUID DEFAULT gen_random_uuid(),
    unit_name   VARCHAR(255) NOT NULL,
    unit_code   VARCHAR(20) NOT NULL,
    unit_type   VARCHAR(50),
    description TEXT,
    CONSTRAINT pk_organizational_units PRIMARY KEY (unit_id),
    CONSTRAINT uq_organizational_units_unit_code UNIQUE (unit_code)
);

CREATE TABLE positions (
    position_id   UUID DEFAULT gen_random_uuid(),
    position_name VARCHAR(50) NOT NULL,
    description   TEXT,
    CONSTRAINT pk_positions PRIMARY KEY (position_id),
    CONSTRAINT uq_positions_position_name UNIQUE (position_name)
);

CREATE TABLE staff (
    staff_id   UUID DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL,
    staff_no   VARCHAR(30) NOT NULL,
    staff_name VARCHAR(255) NOT NULL,
    phone_no   VARCHAR(30),
    batch_year INTEGER,
    address    TEXT,
    unit_id    UUID,
    joined_at  DATE,
    left_date  DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_staff PRIMARY KEY (staff_id),
    CONSTRAINT uq_staff_staff_no UNIQUE (staff_no),
    CONSTRAINT fk_staff_user FOREIGN KEY (user_id)
        REFERENCES users (user_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_staff_unit FOREIGN KEY (unit_id)
        REFERENCES organizational_units (unit_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT chk_staff_left_date CHECK (
        left_date IS NULL OR joined_at IS NULL OR left_date >= joined_at
    )
);

CREATE TABLE staff_position_assignments (
    position_assignment_id UUID DEFAULT gen_random_uuid(),
    staff_id               UUID NOT NULL,
    position_id            UUID NOT NULL,
    start_date             DATE NOT NULL,
    end_date               DATE,
    assigned_by_staff_id   UUID,
    CONSTRAINT pk_staff_position_assignments PRIMARY KEY (position_assignment_id),
    CONSTRAINT uq_staff_position_assignments UNIQUE (staff_id, position_id, start_date),
    CONSTRAINT fk_staff_position_assignments_staff FOREIGN KEY (staff_id)
        REFERENCES staff (staff_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_staff_position_assignments_position FOREIGN KEY (position_id)
        REFERENCES positions (position_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_staff_position_assignments_assigned_by FOREIGN KEY (assigned_by_staff_id)
        REFERENCES staff (staff_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT chk_staff_position_assignments_end_date CHECK (
        end_date IS NULL OR end_date >= start_date
    )
);

-- ============================================================
-- ACADEMIC
-- ============================================================

CREATE TABLE majors (
    major_id   UUID DEFAULT gen_random_uuid(),
    unit_id    UUID NOT NULL,
    major_code VARCHAR(20) NOT NULL,
    major_name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_majors PRIMARY KEY (major_id),
    CONSTRAINT fk_majors_unit FOREIGN KEY (unit_id)
        REFERENCES organizational_units (unit_id)
        ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE TABLE semesters (
    semester_id UUID DEFAULT gen_random_uuid(),
    semester_no INTEGER NOT NULL,
    CONSTRAINT pk_semesters PRIMARY KEY (semester_id),
    CONSTRAINT chk_semesters_semester_no CHECK (semester_no > 0)
);

CREATE TABLE sections (
    section_id   UUID DEFAULT gen_random_uuid(),
    section_name VARCHAR(20) NOT NULL,
    CONSTRAINT pk_sections PRIMARY KEY (section_id)
);

-- Academic terms: academic_year is an integer such as 2026
CREATE TABLE academic_terms (
    term_id       UUID DEFAULT gen_random_uuid(),
    academic_year INTEGER NOT NULL,
    start_date    DATE,
    end_date      DATE,
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT pk_academic_terms PRIMARY KEY (term_id),
    CONSTRAINT chk_academic_terms_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'COMPLETED')),
    CONSTRAINT chk_academic_terms_dates CHECK (
        end_date IS NULL OR start_date IS NULL OR end_date >= start_date
    )
);

CREATE TABLE students (
    student_id   UUID DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL,
    major_id     UUID NOT NULL,
    semester_id  UUID,
    section_id   UUID,
    term_id      UUID,
    roll_no      VARCHAR(30) NOT NULL,
    student_name VARCHAR(255) NOT NULL,
    phone_no     VARCHAR(30),
    address      TEXT,
    birth_year   INTEGER,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_students PRIMARY KEY (student_id),
    CONSTRAINT uq_students_roll_no UNIQUE (roll_no),
    CONSTRAINT fk_students_user FOREIGN KEY (user_id)
        REFERENCES users (user_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_students_major FOREIGN KEY (major_id)
        REFERENCES majors (major_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_students_semester FOREIGN KEY (semester_id)
        REFERENCES semesters (semester_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_students_section FOREIGN KEY (section_id)
        REFERENCES sections (section_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_students_term FOREIGN KEY (term_id)
        REFERENCES academic_terms (term_id)
        ON UPDATE CASCADE ON DELETE RESTRICT
);

-- ============================================================
-- TEACHING
-- ============================================================

CREATE TABLE courses (
    course_id    UUID DEFAULT gen_random_uuid(),
    unit_id      UUID NOT NULL,
    course_code  VARCHAR(20) NOT NULL,
    course_name  VARCHAR(255) NOT NULL,
    credit_unit  INTEGER NOT NULL,
    major_id     UUID,
    semester_id  UUID,
    is_required  BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT pk_courses PRIMARY KEY (course_id),
    CONSTRAINT uq_courses_course_code UNIQUE (course_code),
    CONSTRAINT fk_courses_unit FOREIGN KEY (unit_id)
        REFERENCES organizational_units (unit_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_courses_major FOREIGN KEY (major_id)
        REFERENCES majors (major_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_courses_semester FOREIGN KEY (semester_id)
        REFERENCES semesters (semester_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT chk_courses_credit_unit CHECK (credit_unit > 0),
    CONSTRAINT chk_courses_display_order CHECK (display_order >= 0)
);

CREATE TABLE teaching_assignments (
    assignment_id        UUID DEFAULT gen_random_uuid(),
    course_id            UUID NOT NULL,
    staff_id             UUID NOT NULL,
    section_id           UUID NOT NULL,
    term_id              UUID NOT NULL,
    assignment_status    VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    assigned_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by_staff_id UUID,
    CONSTRAINT pk_teaching_assignments PRIMARY KEY (assignment_id),
    CONSTRAINT fk_teaching_assignments_course FOREIGN KEY (course_id)
        REFERENCES courses (course_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_teaching_assignments_staff FOREIGN KEY (staff_id)
        REFERENCES staff (staff_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_teaching_assignments_section FOREIGN KEY (section_id)
        REFERENCES sections (section_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_teaching_assignments_term FOREIGN KEY (term_id)
        REFERENCES academic_terms (term_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_teaching_assignments_assigned_by FOREIGN KEY (assigned_by_staff_id)
        REFERENCES staff (staff_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT chk_teaching_assignments_status
        CHECK (assignment_status IN ('PENDING', 'ACTIVE', 'COMPLETED', 'CANCELLED'))
);

-- ============================================================
-- TIMETABLE
-- ============================================================

-- NOTE: time_slots intentionally has no slot_type.
-- Slot types are expressed through class_schedules.schedule_type.
CREATE TABLE time_slots (
    slot_id       UUID DEFAULT gen_random_uuid(),
    period_no     INTEGER NOT NULL,
    start_time    TIME NOT NULL,
    end_time      TIME NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT pk_time_slots PRIMARY KEY (slot_id),
    CONSTRAINT chk_time_slots_period_no CHECK (period_no > 0),
    CONSTRAINT chk_time_slots_times CHECK (end_time > start_time),
    CONSTRAINT chk_time_slots_display_order CHECK (display_order >= 0)
);

CREATE TABLE course_meeting_requirements (
    requirement_id      UUID DEFAULT gen_random_uuid(),
    course_id           UUID NOT NULL,
    meeting_type        VARCHAR(20) NOT NULL,
    sessions_per_week   INTEGER NOT NULL,
    periods_per_session INTEGER NOT NULL,
    CONSTRAINT pk_course_meeting_requirements PRIMARY KEY (requirement_id),
    CONSTRAINT uq_course_meeting_requirements UNIQUE (course_id, meeting_type),
    CONSTRAINT fk_course_meeting_requirements_course FOREIGN KEY (course_id)
        REFERENCES courses (course_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT chk_course_meeting_requirements_type
        CHECK (meeting_type IN ('LECTURE', 'LAB')),
    CONSTRAINT chk_course_meeting_requirements_sessions CHECK (sessions_per_week > 0),
    CONSTRAINT chk_course_meeting_requirements_periods CHECK (periods_per_session > 0)
);

CREATE TABLE generation_sessions (
    generation_id         UUID DEFAULT gen_random_uuid(),
    term_id               UUID NOT NULL,
    generated_by_staff_id UUID NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    started_at            TIMESTAMPTZ,
    published_at          TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_generation_sessions PRIMARY KEY (generation_id),
    CONSTRAINT fk_generation_sessions_term FOREIGN KEY (term_id)
        REFERENCES academic_terms (term_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_generation_sessions_generated_by FOREIGN KEY (generated_by_staff_id)
        REFERENCES staff (staff_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT chk_generation_sessions_status
        CHECK (status IN ('PENDING', 'GENERATING', 'COMPLETED', 'FAILED', 'PUBLISHED'))
);

-- day_of_week: 1 = Monday ... 7 = Sunday
CREATE TABLE class_schedules (
    schedule_id            UUID DEFAULT gen_random_uuid(),
    generation_id          UUID NOT NULL,
    teaching_assignment_id UUID,
    day_of_week            INTEGER NOT NULL,
    start_slot_id          UUID NOT NULL,
    end_slot_id            UUID NOT NULL,
    schedule_status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    schedule_type          VARCHAR(20) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_class_schedules PRIMARY KEY (schedule_id),
    CONSTRAINT fk_class_schedules_generation FOREIGN KEY (generation_id)
        REFERENCES generation_sessions (generation_id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_class_schedules_teaching_assignment FOREIGN KEY (teaching_assignment_id)
        REFERENCES teaching_assignments (assignment_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_class_schedules_start_slot FOREIGN KEY (start_slot_id)
        REFERENCES time_slots (slot_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_class_schedules_end_slot FOREIGN KEY (end_slot_id)
        REFERENCES time_slots (slot_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT chk_class_schedules_day_of_week CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_class_schedules_status
        CHECK (schedule_status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT chk_class_schedules_type
        CHECK (schedule_type IN ('COURSE', 'LMS', 'ASSIGNMENT', 'BREAK')),
    CONSTRAINT chk_class_schedules_teaching_assignment CHECK (
        (schedule_type = 'COURSE' AND teaching_assignment_id IS NOT NULL)
        OR
        (schedule_type IN ('LMS', 'ASSIGNMENT', 'BREAK') AND teaching_assignment_id IS NULL)
    )
);

-- ============================================================
-- ATTENDANCE
-- ============================================================

CREATE TABLE class_sessions (
    session_id     UUID DEFAULT gen_random_uuid(),
    schedule_id    UUID NOT NULL,
    session_date   DATE NOT NULL,
    session_status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    started_at     TIMESTAMPTZ,
    ended_at       TIMESTAMPTZ,
    CONSTRAINT pk_class_sessions PRIMARY KEY (session_id),
    CONSTRAINT fk_class_sessions_schedule FOREIGN KEY (schedule_id)
        REFERENCES class_schedules (schedule_id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_class_sessions_status
        CHECK (session_status IN ('SCHEDULED', 'ONGOING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_class_sessions_times CHECK (
        ended_at IS NULL OR started_at IS NULL OR ended_at > started_at
    )
);

CREATE TABLE attendance (
    attendance_id      UUID DEFAULT gen_random_uuid(),
    session_id         UUID NOT NULL,
    student_id         UUID NOT NULL,
    attendance_status  VARCHAR(20) NOT NULL,
    remark             TEXT,
    marked_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    marked_by_staff_id UUID,
    CONSTRAINT pk_attendance PRIMARY KEY (attendance_id),
    CONSTRAINT uq_attendance_session_student UNIQUE (session_id, student_id),
    CONSTRAINT fk_attendance_session FOREIGN KEY (session_id)
        REFERENCES class_sessions (session_id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_attendance_student FOREIGN KEY (student_id)
        REFERENCES students (student_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_attendance_marked_by FOREIGN KEY (marked_by_staff_id)
        REFERENCES staff (staff_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT chk_attendance_status
        CHECK (attendance_status IN ('PRESENT', 'ABSENT'))
);

-- ============================================================
-- EXAMINATIONS
-- ============================================================

CREATE TABLE exam_types (
    exam_type_id   UUID DEFAULT gen_random_uuid(),
    exam_type_name VARCHAR(50) NOT NULL,
    CONSTRAINT pk_exam_types PRIMARY KEY (exam_type_id),
    CONSTRAINT uq_exam_types_exam_type_name UNIQUE (exam_type_name)
);

CREATE TABLE result_batches (
    batch_id             UUID DEFAULT gen_random_uuid(),
    term_id              UUID NOT NULL,
    exam_type_id         UUID NOT NULL,
    semester_id          UUID NOT NULL,
    uploaded_by_staff_id UUID NOT NULL,
    uploaded_type        VARCHAR(50),
    source_file_name     VARCHAR(255),
    total_files          INTEGER NOT NULL DEFAULT 0,
    matched_files        INTEGER NOT NULL DEFAULT 0,
    failed_files         INTEGER NOT NULL DEFAULT 0,
    status               VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',
    uploaded_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at         TIMESTAMPTZ,
    CONSTRAINT pk_result_batches PRIMARY KEY (batch_id),
    CONSTRAINT fk_result_batches_term FOREIGN KEY (term_id)
        REFERENCES academic_terms (term_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_result_batches_exam_type FOREIGN KEY (exam_type_id)
        REFERENCES exam_types (exam_type_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_result_batches_semester FOREIGN KEY (semester_id)
        REFERENCES semesters (semester_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_result_batches_uploaded_by FOREIGN KEY (uploaded_by_staff_id)
        REFERENCES staff (staff_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT chk_result_batches_status
        CHECK (status IN ('UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED', 'PUBLISHED')),
    CONSTRAINT chk_result_batches_total_files CHECK (total_files >= 0),
    CONSTRAINT chk_result_batches_matched_files CHECK (matched_files >= 0),
    CONSTRAINT chk_result_batches_failed_files CHECK (failed_files >= 0)
);

CREATE TABLE exam_result_documents (
    result_document_id  UUID DEFAULT gen_random_uuid(),
    batch_id            UUID NOT NULL,
    student_id          UUID NOT NULL,
    pdf_file_name       VARCHAR(255) NOT NULL,
    storage_object_path TEXT NOT NULL,
    release_status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    blocked_reason      TEXT,
    viewed_at           TIMESTAMPTZ,
    downloaded_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_exam_result_documents PRIMARY KEY (result_document_id),
    CONSTRAINT uq_exam_result_documents UNIQUE (batch_id, student_id),
    CONSTRAINT fk_exam_result_documents_batch FOREIGN KEY (batch_id)
        REFERENCES result_batches (batch_id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_exam_result_documents_student FOREIGN KEY (student_id)
        REFERENCES students (student_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT chk_exam_result_documents_release_status
        CHECK (release_status IN ('PENDING', 'RELEASED', 'BLOCKED'))
);

-- ============================================================
-- INDEXES
-- ============================================================
-- NOTE: no index is created for a column whose leftmost prefix is
-- already covered by a UNIQUE constraint (e.g. users(email),
-- students(roll_no), staff(staff_no), courses(course_code),
-- course_meeting_requirements(course_id), attendance(session_id),
-- exam_result_documents(batch_id)).

CREATE INDEX idx_users_role_id ON users (role_id);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

CREATE INDEX idx_staff_user_id ON staff (user_id);
CREATE INDEX idx_staff_unit_id ON staff (unit_id);

CREATE INDEX idx_staff_position_assignments_staff_id ON staff_position_assignments (staff_id);
CREATE INDEX idx_staff_position_assignments_position_id ON staff_position_assignments (position_id);
CREATE INDEX idx_staff_position_assignments_assigned_by ON staff_position_assignments (assigned_by_staff_id);

CREATE INDEX idx_majors_unit_id ON majors (unit_id);

CREATE INDEX idx_students_user_id ON students (user_id);
CREATE INDEX idx_students_major_id ON students (major_id);
CREATE INDEX idx_students_semester_id ON students (semester_id);
CREATE INDEX idx_students_section_id ON students (section_id);
CREATE INDEX idx_students_term_id ON students (term_id);

CREATE INDEX idx_courses_unit_id ON courses (unit_id);
CREATE INDEX idx_courses_major_id ON courses (major_id);
CREATE INDEX idx_courses_semester_id ON courses (semester_id);

CREATE INDEX idx_teaching_assignments_course_id ON teaching_assignments (course_id);
CREATE INDEX idx_teaching_assignments_staff_id ON teaching_assignments (staff_id);
CREATE INDEX idx_teaching_assignments_section_id ON teaching_assignments (section_id);
CREATE INDEX idx_teaching_assignments_term_id ON teaching_assignments (term_id);
CREATE INDEX idx_teaching_assignments_assigned_by ON teaching_assignments (assigned_by_staff_id);

CREATE INDEX idx_generation_sessions_term_id ON generation_sessions (term_id);
CREATE INDEX idx_generation_sessions_generated_by ON generation_sessions (generated_by_staff_id);

CREATE INDEX idx_class_schedules_generation_id ON class_schedules (generation_id);
CREATE INDEX idx_class_schedules_teaching_assignment_id ON class_schedules (teaching_assignment_id);
CREATE INDEX idx_class_schedules_day_of_week ON class_schedules (day_of_week);
CREATE INDEX idx_class_schedules_start_slot_id ON class_schedules (start_slot_id);
CREATE INDEX idx_class_schedules_end_slot_id ON class_schedules (end_slot_id);
-- Timetable lookup: section/course/term + weekday + period
CREATE INDEX idx_class_schedules_teaching_day_slot
    ON class_schedules (teaching_assignment_id, day_of_week, start_slot_id);

CREATE INDEX idx_class_sessions_schedule_id ON class_sessions (schedule_id);
CREATE INDEX idx_class_sessions_session_date ON class_sessions (session_date);

CREATE INDEX idx_attendance_student_id ON attendance (student_id);
CREATE INDEX idx_attendance_marked_by ON attendance (marked_by_staff_id);

CREATE INDEX idx_result_batches_term_id ON result_batches (term_id);
CREATE INDEX idx_result_batches_exam_type_id ON result_batches (exam_type_id);
CREATE INDEX idx_result_batches_semester_id ON result_batches (semester_id);
CREATE INDEX idx_result_batches_uploaded_by ON result_batches (uploaded_by_staff_id);

CREATE INDEX idx_exam_result_documents_student_id ON exam_result_documents (student_id);