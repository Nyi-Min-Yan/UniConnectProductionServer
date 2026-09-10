-- ============================================================
-- UniConnect - University Management System
-- Migration 004: Standardize organizational unit types
-- Target: Neon PostgreSQL
--
-- unit_type is normalized to exactly two values:
--   ACADEMIC       - faculties + academic departments
--   ADMINISTRATIVE - administration, finance, student affairs
--
-- Idempotent: safe to run any number of times.
-- ============================================================

BEGIN;

-- 7 academic units: FCS(CS), FCST(CST), FIS(IS), ITSM, FC(COMP),
--                   DNL(NL), DNS(NS)
UPDATE organizational_units
SET unit_type = 'ACADEMIC'
WHERE unit_code IN ('CS', 'CST', 'IS', 'ITSM', 'COMP', 'NL', 'NS')
  AND unit_type IS DISTINCT FROM 'ACADEMIC';

-- 3 administrative units: ADMIN(ADM), FINANCE(FIN),
--                         STUDENT_AFFAIRS(SA)
UPDATE organizational_units
SET unit_type = 'ADMINISTRATIVE'
WHERE unit_code IN ('ADM', 'FIN', 'SA')
  AND unit_type IS DISTINCT FROM 'ADMINISTRATIVE';

-- Enforce the two allowed values from now on (dropped first so the
-- migration is re-runnable).
ALTER TABLE organizational_units DROP CONSTRAINT IF EXISTS chk_organizational_units_unit_type;
ALTER TABLE organizational_units
    ADD CONSTRAINT chk_organizational_units_unit_type
    CHECK (unit_type IN ('ACADEMIC', 'ADMINISTRATIVE'));

COMMIT;