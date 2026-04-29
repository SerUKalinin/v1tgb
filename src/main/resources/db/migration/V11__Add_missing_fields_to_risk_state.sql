-- Migration: Add missing fields to risk_state to match RiskStateEntity
-- Description: Adds active_reservations, processed_event_ids and enforces constraints

-- 1. Add columns for serialized data (Map and Set)
-- Using TEXT as explicitly defined in RiskStateEntity columnDefinition
ALTER TABLE risk_state ADD COLUMN IF NOT EXISTS active_reservations TEXT;
ALTER TABLE risk_state ADD COLUMN IF NOT EXISTS processed_event_ids TEXT;

-- 2. Align existing columns with Entity constraints (nullable = false)
-- Fill NULL values before setting NOT NULL to avoid production migration failure
UPDATE risk_state SET total_equity = 0 WHERE total_equity IS NULL;
ALTER TABLE risk_state ALTER COLUMN total_equity SET NOT NULL;

UPDATE risk_state SET available_balance = 0 WHERE available_balance IS NULL;
ALTER TABLE risk_state ALTER COLUMN available_balance SET NOT NULL;

UPDATE risk_state SET reserved_margin = 0 WHERE reserved_margin IS NULL;
ALTER TABLE risk_state ALTER COLUMN reserved_margin SET NOT NULL;

UPDATE risk_state SET halted = FALSE WHERE halted IS NULL;
ALTER TABLE risk_state ALTER COLUMN halted SET NOT NULL;
