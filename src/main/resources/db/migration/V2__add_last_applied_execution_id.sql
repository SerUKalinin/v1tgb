-- V2__add_last_applied_execution_id.sql
-- Разделение ownership (executionId) и идемпотентности fill() (lastAppliedExecutionId)

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS last_applied_execution_id UUID;
