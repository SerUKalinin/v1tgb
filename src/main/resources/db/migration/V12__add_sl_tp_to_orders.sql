-- Migration for Stage 3.5: Trade Lifecycle Engine
-- Adding TP/SL columns to orders table to persist exit targets
-- Adding lifecycle columns to positions table

-- Таблица orders
ALTER TABLE orders ADD COLUMN IF NOT EXISTS stop_loss DECIMAL(18, 8);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS take_profit DECIMAL(18, 8);

-- Таблица positions
ALTER TABLE positions ADD COLUMN IF NOT EXISTS stop_loss DECIMAL(18, 8);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS take_profit DECIMAL(18, 8);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS status VARCHAR(20);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS close_request_id UUID;
ALTER TABLE positions ADD COLUMN IF NOT EXISTS last_trade_id BIGINT;
ALTER TABLE positions ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;
