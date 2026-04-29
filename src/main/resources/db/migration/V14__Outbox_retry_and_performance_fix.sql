-- Migration: V14__Outbox_retry_and_performance_fix
-- Description: Adds next_attempt_at for backoff and missing indexes for performance

-- 1. Add next_attempt_at for scheduled retries
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

-- 2. Optimized partial index for outbox polling
-- This index is small and fast as it only includes active events
CREATE INDEX IF NOT EXISTS idx_outbox_polling_v3 ON outbox_events (status, next_attempt_at) 
WHERE status IN ('PENDING', 'FAILED', 'PROCESSING');

-- 3. Missing index for position reconciliation (from previous audit)
CREATE INDEX IF NOT EXISTS idx_positions_last_trade_id ON positions(last_trade_id);
