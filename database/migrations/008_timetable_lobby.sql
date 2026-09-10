-- Timetable generation lobby: an HOD creates a lobby and invites the other
-- HOD lecturers to join; generation is only allowed once every invited HOD
-- has joined. Only one active lobby may exist at a time.

CREATE TABLE timetable_lobbies (
    lobby_id          UUID DEFAULT gen_random_uuid(),
    term_id           UUID NOT NULL,
    leader_staff_id   UUID NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    generation_id     UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_timetable_lobbies PRIMARY KEY (lobby_id),
    CONSTRAINT fk_timetable_lobbies_term FOREIGN KEY (term_id)
        REFERENCES academic_terms (term_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_timetable_lobbies_leader FOREIGN KEY (leader_staff_id)
        REFERENCES staff (staff_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_timetable_lobbies_generation FOREIGN KEY (generation_id)
        REFERENCES generation_sessions (generation_id)
        ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT chk_timetable_lobbies_status
        CHECK (status IN ('OPEN', 'GENERATING', 'COMPLETED', 'CANCELLED'))
);

CREATE TABLE timetable_lobby_members (
    member_id   UUID DEFAULT gen_random_uuid(),
    lobby_id    UUID NOT NULL,
    staff_id    UUID NOT NULL,
    invited_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    joined_at   TIMESTAMPTZ,
    CONSTRAINT pk_timetable_lobby_members PRIMARY KEY (member_id),
    CONSTRAINT uq_timetable_lobby_members UNIQUE (lobby_id, staff_id),
    CONSTRAINT fk_timetable_lobby_members_lobby FOREIGN KEY (lobby_id)
        REFERENCES timetable_lobbies (lobby_id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_timetable_lobby_members_staff FOREIGN KEY (staff_id)
        REFERENCES staff (staff_id)
        ON UPDATE CASCADE ON DELETE RESTRICT
);
