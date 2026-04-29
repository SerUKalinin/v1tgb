-- Migration: V12__Reconciliation_and_precision_fix
-- Description: Fixes decimal precision, outbox types, and adds missing indexes

-- 1. Fix Decimal Precision for financial data (Money loss prevention)
ALTER TABLE orders ALTER COLUMN quantity TYPE DECIMAL(38, 18);
ALTER TABLE orders ALTER COLUMN price TYPE DECIMAL(38, 18);
ALTER TABLE orders ALTER COLUMN stop_loss TYPE DECIMAL(38, 18);
ALTER TABLE orders ALTER COLUMN take_profit TYPE DECIMAL(38, 18);

ALTER TABLE risk_state ALTER COLUMN total_equity TYPE DECIMAL(38, 18);
ALTER TABLE risk_state ALTER COLUMN available_balance TYPE DECIMAL(38, 18);
ALTER TABLE risk_state ALTER COLUMN reserved_margin TYPE DECIMAL(38, 18);

ALTER TABLE positions ALTER COLUMN net_quantity TYPE DECIMAL(38, 18);
ALTER TABLE positions ALTER COLUMN avg_entry_price TYPE DECIMAL(38, 18);
ALTER TABLE positions ALTER COLUMN realized_pnl TYPE DECIMAL(38, 18);
ALTER TABLE positions ALTER COLUMN stop_loss TYPE DECIMAL(38, 18);
ALTER TABLE positions ALTER COLUMN take_profit TYPE DECIMAL(38, 18);

-- 2. Fix Outbox Types (Idempotency & Mapping)
-- Convert aggregate_id to UUID (requires explicit cast)
ALTER TABLE outbox_events ALTER COLUMN aggregate_id TYPE UUID USING aggregate_id::UUID;
-- Fix Timezone for locked_until
ALTER TABLE outbox_events ALTER COLUMN locked_until TYPE TIMESTAMP WITH TIME ZONE;

-- 3. Performance Optimization (Missing Indexes)
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_positions_status ON positions(status);
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_id ON outbox_events(aggregate_id);

-- 4. Schema Hygiene
ALTER TABLE risk_state ALTER COLUMN id TYPE VARCHAR(20);
