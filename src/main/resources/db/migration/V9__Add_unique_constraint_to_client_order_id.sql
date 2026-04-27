-- V9__Add_unique_constraint_to_client_order_id.sql
-- Гарантируем уникальность client_order_id на уровне БД для предотвращения дубликатов ордеров

-- Индекс уже существует из V1, но мы пересоздаем его как UNIQUE для жесткой гарантии
DROP INDEX IF EXISTS idx_orders_client_order_id;
CREATE UNIQUE INDEX idx_orders_client_order_id ON orders(client_order_id);
