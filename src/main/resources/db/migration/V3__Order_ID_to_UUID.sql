-- V3__Order_ID_to_UUID.sql
-- Controlled refactor: VARCHAR -> UUID for Order IDs

-- 1. trades table: drop FK and change column type
ALTER TABLE trades DROP CONSTRAINT IF EXISTS trades_order_id_fkey;

-- 2. orders table: change PK type
-- Note: Using USING clause for explicit conversion
ALTER TABLE orders ALTER COLUMN id TYPE UUID USING id::UUID;

-- 3. trades table: change FK column type and restore FK
ALTER TABLE trades ALTER COLUMN order_id TYPE UUID USING order_id::UUID;
ALTER TABLE trades ADD CONSTRAINT trades_order_id_fkey FOREIGN KEY (order_id) REFERENCES orders(id);

-- 4. outbox_events table: change aggregate_id type
ALTER TABLE outbox_events ALTER COLUMN aggregate_id TYPE UUID USING aggregate_id::UUID;

-- 5. positions table: close_request_id is already UUID in schema but let's ensure consistency if needed
-- (Based on V1 it was already UUID, so no change needed there)
