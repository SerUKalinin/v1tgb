-- Migration: Add event_id and aggregate_id to outbox_events
-- Part of P0 FINAL FIX — DETERMINISTIC IDENTITY MODEL HARDENING

ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS event_id UUID;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS aggregate_id UUID;

-- Update existing records to maintain consistency where possible
UPDATE outbox_events SET event_id = id WHERE event_id IS NULL;
UPDATE outbox_events SET aggregate_id = signal_id WHERE aggregate_id IS NULL AND signal_id IS NOT NULL;

-- Add constraints
ALTER TABLE outbox_events ALTER COLUMN event_id SET NOT NULL;
ALTER TABLE outbox_events ADD CONSTRAINT uk_outbox_event_id UNIQUE (event_id);

-- execution_id is now nullable according to new semantics
ALTER TABLE outbox_events ALTER COLUMN execution_id DROP NOT NULL;
