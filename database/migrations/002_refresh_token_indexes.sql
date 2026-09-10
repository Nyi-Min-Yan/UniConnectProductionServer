-- ============================================================
-- Migration 002: Additional refresh-token indexes
-- ============================================================

-- For cleanup/expiration queries
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at
    ON refresh_tokens (expires_at);

-- Partial index for active tokens (used by findByUserAndRevokedFalse)
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_active
    ON refresh_tokens (user_id)
    WHERE revoked = false;
