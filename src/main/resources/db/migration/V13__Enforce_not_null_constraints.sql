-- Migration: V13__Enforce_not_null_constraints
-- Description: Aligns database schema with JPA Entity nullable=false constraints

-- 1. Positions table constraints
ALTER TABLE positions ALTER COLUMN symbol SET NOT NULL;
ALTER TABLE positions ALTER COLUMN strategy_id SET NOT NULL;

-- 2. Outbox events table constraints
ALTER TABLE outbox_events ALTER COLUMN payload SET NOT NULL;

-- 3. Orders table constraints (ensure consistency)
ALTER TABLE orders ALTER COLUMN symbol SET NOT NULL;
ALTER TABLE orders ALTER COLUMN strategy_id SET NOT NULL;
ALTER TABLE orders ALTER COLUMN side SET NOT NULL;
ALTER TABLE orders ALTER COLUMN type SET NOT NULL;
ALTER TABLE orders ALTER COLUMN status SET NOT NULL;
