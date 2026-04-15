-- V2__Enhanced_schema.sql
-- Расширение таблиц для поддержки статусов, стратегий и аналитики

-- 1. Обновление таблицы orders
ALTER TABLE orders ADD COLUMN IF NOT EXISTS strategy_id VARCHAR(50) DEFAULT 'default';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'NEW';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

-- 2. Обновление таблицы trades
ALTER TABLE trades ADD COLUMN IF NOT EXISTS external_trade_id VARCHAR(255);
ALTER TABLE trades ADD COLUMN IF NOT EXISTS strategy_id VARCHAR(50) DEFAULT 'default';
ALTER TABLE trades ADD COLUMN IF NOT EXISTS commission DECIMAL(20, 8) DEFAULT 0;
ALTER TABLE trades ADD COLUMN IF NOT EXISTS commission_asset VARCHAR(10);
ALTER TABLE trades ADD COLUMN IF NOT EXISTS sequence_id BIGINT;

CREATE SEQUENCE IF NOT EXISTS trades_sequence_id_seq;
ALTER TABLE trades ALTER COLUMN sequence_id SET DEFAULT nextval('trades_sequence_id_seq');

-- 3. Обновление таблицы positions
-- Пересоздаем таблицу для поддержки составного ключа (symbol, strategy_id)
DROP TABLE IF EXISTS positions CASCADE;
CREATE TABLE positions (
    symbol VARCHAR(20) NOT NULL,
    strategy_id VARCHAR(50) NOT NULL,
    quantity DECIMAL(20, 8) NOT NULL DEFAULT 0,
    entry_price DECIMAL(20, 8) NOT NULL DEFAULT 0,
    realized_pnl DECIMAL(20, 8) NOT NULL DEFAULT 0,
    last_trade_id BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (symbol, strategy_id)
);

-- 4. Таблица для аналитики (Equity Snapshots)
CREATE TABLE IF NOT EXISTS equity_snapshots (
    id BIGSERIAL PRIMARY KEY,
    strategy_id VARCHAR(50) NOT NULL,
    balance DECIMAL(20, 8) NOT NULL,
    equity DECIMAL(20, 8) NOT NULL,
    unrealized_pnl DECIMAL(20, 8) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_equity_snapshots_strategy_timestamp ON equity_snapshots(strategy_id, timestamp);
