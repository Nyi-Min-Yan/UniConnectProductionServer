-- 006: Seed exam types and enforce one result batch per (term, exam_type, semester).
-- Idempotent: safe to re-run.

INSERT INTO exam_types (exam_type_id, exam_type_name)
VALUES
    (md5('exam_types:MID-TERM')::uuid, 'Mid Term'),
    (md5('exam_types:FINAL-TERM')::uuid, 'Final Term')
ON CONFLICT (exam_type_name) DO NOTHING;

-- Enforce batch reuse/concurrency safety: at most one batch per (term, exam_type, semester).
CREATE UNIQUE INDEX IF NOT EXISTS uq_result_batches_term_exam_sem
    ON result_batches (term_id, exam_type_id, semester_id);