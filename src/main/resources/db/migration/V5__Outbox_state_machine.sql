-- V5__Outbox_state_machine.sql
-- Refactor outbox to support state machine and reliable processing

ALTER TABLE outbox_events ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'NEW';
ALTER TABLE outbox_events ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

-- Update existing processed events
UPDATE outbox_events SET status = 'PROCESSED' WHERE processed_at IS NOT NULL;

-- Index for efficient polling
CREATE INDEX idx_outbox_status_created ON outbox_events(status, created_at) WHERE status IN ('NEW', 'FAILED');
CREATE INDEX idx_outbox_stale_processing ON outbox_events(status, updated_at) WHERE status = 'PROCESSING';
