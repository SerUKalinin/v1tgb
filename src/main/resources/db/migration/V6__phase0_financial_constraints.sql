-- 1. Защита Risk Core (Aggregate Row)
ALTER TABLE risk_state 
    ADD CONSTRAINT chk_risk_balance_positive CHECK (available_balance >= 0),
    ADD CONSTRAINT chk_risk_reserved_limit CHECK (reserved_margin <= total_equity),
    ALTER COLUMN total_equity SET NOT NULL,
    ALTER COLUMN available_balance SET NOT NULL,
    ALTER COLUMN reserved_margin SET NOT NULL;

-- Инициализация единственной строки агрегата, если её нет
-- Используем подзапрос для совместимости с H2 и PostgreSQL
INSERT INTO risk_state (id, total_equity, available_balance, reserved_margin, halted, version, updated_at)
SELECT 'risk_core', 0.0, 0.0, 0.0, false, 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM risk_state WHERE id = 'risk_core');

-- 2. Ограничения для Orders
ALTER TABLE orders 
    ADD CONSTRAINT uq_orders_client_id UNIQUE (client_order_id),
    ADD CONSTRAINT chk_order_qty_positive CHECK (quantity > 0),
    ALTER COLUMN client_order_id SET NOT NULL,
    ALTER COLUMN symbol SET NOT NULL;

-- 3. Ограничения для Trades
ALTER TABLE trades 
    ADD CONSTRAINT chk_trade_qty_positive CHECK (quantity > 0),
    ALTER COLUMN order_id SET NOT NULL,
    ALTER COLUMN symbol SET NOT NULL;

-- 4. Идемпотентность потребителей (Processed Events)
CREATE TABLE IF NOT EXISTS processed_events (
    event_id UUID PRIMARY KEY,
    consumer_name VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT NOW()
);
