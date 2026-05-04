-- Add execution_id and enforce unique client_order_id
ALTER TABLE orders ADD COLUMN IF NOT EXISTS execution_id UUID;

-- Ensure client_order_id is unique and not null (if not already)
-- Note: This might fail if there are duplicates, but for a clean migration it's required.
ALTER TABLE orders ALTER COLUMN client_order_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_orders_client_order_id ON orders(client_order_id);
