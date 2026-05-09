-- Migration: Add lease metadata columns to outbox_events (non-destructive)
ALTER TABLE outbox_events ADD COLUMN claimed_by VARCHAR(128);
ALTER TABLE outbox_events ADD COLUMN claimed_at TIMESTAMP;
ALTER TABLE outbox_events ADD COLUMN lease_until TIMESTAMP;
