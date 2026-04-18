-- =============================================================================
-- V1776524454__auth_slice1_support_tables.sql
-- Source: auth-service-design.md §4.1, §4.3.
--
-- NOTE (2026-04-18): Phiên bản ban đầu của migration này CREATE TABLE
-- password_history + email_verification_tokens với token_hash VARCHAR(64).
-- Tuy nhiên V0001__baseline_schema.sql đã định nghĩa 2 bảng này với
-- token_hash BYTEA → Flyway fail trên DB trống ("relation already exists").
--
-- Giữ file migration để không gap version, convert thành no-op bằng DO block
-- idempotent. Không xoá bảng của V0001 (nguồn truth duy nhất cho schema auth).
--
-- Cross-service impact: không có — 2 bảng này chỉ Auth Service dùng.
-- =============================================================================

DO $$
BEGIN
    -- Chỉ log; các bảng thực tế được tạo ở V0001.
    RAISE NOTICE 'V1776524454: auth slice1 tables already provided by V0001 baseline — no-op';
END$$;
