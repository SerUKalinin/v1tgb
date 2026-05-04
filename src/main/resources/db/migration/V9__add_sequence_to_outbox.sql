-- Migration to add sequence_number to outbox_events (safe step)
ALTER TABLE outbox_events ADD COLUMN sequence_number BIGINT;
