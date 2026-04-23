-- V4__Add_optimistic_locking_to_orders.sql
-- Phase 0 hardening: Add version column for optimistic locking

ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
