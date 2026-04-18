-- =============================================================================
-- V1776540000__auth_refresh_active_org.sql
-- Source: auth-service-design.md §12.2 (POST /auth/switch-org).
--
-- Lý do: user có nhiều membership, JWT có claim `org_id` cho org active hiện tại.
-- Khi refresh, service cần biết org nào để set lại claim — hiện tại fallback về
-- membership đầu tiên (sai nếu user đã switch-org). Persist `active_org_id` vào
-- refresh_tokens để refresh + switch-org giữ ngữ cảnh org chính xác.
--
-- Cross-service impact: không — refresh_tokens chỉ Auth Service dùng.
-- =============================================================================

BEGIN;

ALTER TABLE refresh_tokens
    ADD COLUMN active_org_id UUID REFERENCES organizations(id) ON DELETE SET NULL;

-- Không index — switch-org + refresh query bằng token_hash (đã unique index),
-- active_org_id chỉ đọc kèm theo row. Nếu cần list sessions theo org sau này,
-- thêm index riêng.

COMMIT;
