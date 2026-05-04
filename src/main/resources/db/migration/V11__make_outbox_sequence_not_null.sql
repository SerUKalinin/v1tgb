-- Migration to make sequence_number NOT NULL in outbox_events
-- First, ensure all existing records have a value (if any were created during development)
UPDATE outbox_events SET sequence_number = 0 WHERE sequence_number IS NULL;

ALTER TABLE outbox_events
ALTER COLUMN sequence_number SET NOT NULL;
