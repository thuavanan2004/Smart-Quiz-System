-- =============================================================================
-- V1776524454__auth_slice1_support_tables.sql
-- Source: auth-service-design.md §4.1, §4.3. Slice 1 scaffold (register/verify/login).
--
-- Mục đích:
--   1. password_history — chống tái dùng 5 mật khẩu gần nhất (policy §6.2).
--   2. email_verification_tokens — token cho verify_email và reset_password,
--      lưu SHA-256 hash (không plaintext).
--
-- Cross-service impact: cả 2 bảng chỉ Auth Service dùng → an toàn.
-- =============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. password_history
-- ---------------------------------------------------------------------------
CREATE TABLE password_history (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Query pattern: "lấy 5 hash gần nhất của user X để check trùng" →
-- (user_id, changed_at DESC) phục vụ trực tiếp.
CREATE INDEX idx_password_history_user ON password_history(user_id, changed_at DESC);

-- ---------------------------------------------------------------------------
-- 2. email_verification_tokens
-- ---------------------------------------------------------------------------
-- token_hash PK = SHA-256 của random 32 bytes (lưu hex 64 ký tự). Không lưu
-- plaintext để compromise DB ≠ compromise token.
--
-- purpose: 'verify_email' (TTL 24h) | 'reset_password' (TTL 1h)
-- used_at: NULL → chưa dùng; NOT NULL → đã dùng (single-use).
CREATE TABLE email_verification_tokens (
    token_hash  VARCHAR(64) PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose     VARCHAR(20) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_email_token_purpose CHECK (purpose IN ('verify_email', 'reset_password'))
);

CREATE INDEX idx_email_token_user    ON email_verification_tokens(user_id, purpose);
CREATE INDEX idx_email_token_expires ON email_verification_tokens(expires_at)
    WHERE used_at IS NULL;

COMMIT;
