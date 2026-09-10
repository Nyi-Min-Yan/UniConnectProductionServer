-- ============================================================
-- UniConnect - University Management System
-- Migration 007: Rename STUDENTS.birth_year -> batch_year
-- Target: Neon PostgreSQL
--
-- The student "batch year" is the year the student entered the
-- program (e.g. 2019), not their birth year. Staff already uses
-- batch_year; this keeps the two profile tables consistent.
--
-- NOTE: PostgreSQL does not support "RENAME COLUMN IF EXISTS", so this
-- migration must be run exactly once against the live database (it has
-- already been applied). Column data is preserved by the rename.
-- ============================================================

ALTER TABLE students RENAME COLUMN birth_year TO batch_year;
