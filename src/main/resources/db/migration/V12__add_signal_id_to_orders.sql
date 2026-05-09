ALTER TABLE orders
    ADD COLUMN signal_id VARCHAR(255) NOT NULL DEFAULT '';
CREATE INDEX idx_orders_signal_id ON orders(signal_id);
