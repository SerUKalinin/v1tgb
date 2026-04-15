-- V3__Trade_ledger_foundation.sql

-- 1. Усиление таблицы trades (Источник истины)
-- Добавляем уникальность для внешнего ID сделки, чтобы избежать дублей при ретраях
ALTER TABLE trades ADD COLUMN IF NOT EXISTS exchange_trade_id VARCHAR(255);
-- Если external_trade_id уже использовался, переносим данные или просто создаем новый индекс
CREATE UNIQUE INDEX IF NOT EXISTS uk_trades_exchange_trade_id ON trades(exchange_trade_id) WHERE exchange_trade_id IS NOT NULL;

-- 2. Усиление таблицы orders
ALTER TABLE orders ADD COLUMN IF NOT EXISTS exchange_order_id VARCHAR(255);
-- Индекс для быстрого поиска по ID биржи
CREATE INDEX IF NOT EXISTS idx_orders_exchange_order_id ON orders(exchange_order_id);

-- 3. Подготовка позиций к модели "Rebuild from Trades"
-- Мы оставляем таблицу как кэш, но меняем семантику
ALTER TABLE positions RENAME COLUMN quantity TO net_quantity;
ALTER TABLE positions RENAME COLUMN entry_price TO avg_entry_price;

-- 4. Индексы для ускорения Replay (восстановления состояния)
CREATE INDEX IF NOT EXISTS idx_trades_symbol_strategy_time ON trades(symbol, strategy_id, executed_at);
