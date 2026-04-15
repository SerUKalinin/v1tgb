-- V4__Add_client_order_id_to_trades.sql

ALTER TABLE trades
    ADD COLUMN IF NOT EXISTS client_order_id VARCHAR(64);

-- Заполняем дефолтными значениями для существующих записей (если есть)
UPDATE trades
SET client_order_id = CONCAT('legacy_', id)
WHERE client_order_id IS NULL;

-- Делаем поле NOT NULL и UNIQUE
ALTER TABLE trades
    ALTER COLUMN client_order_id SET NOT NULL;

ALTER TABLE trades
    ADD CONSTRAINT uk_trades_client_order_id UNIQUE (client_order_id);

-- Индекс для быстрого поиска
CREATE INDEX IF NOT EXISTS idx_trades_client_order_id ON trades(client_order_id);