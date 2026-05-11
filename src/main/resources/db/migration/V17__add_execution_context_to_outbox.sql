-- Миграция для внедрения детерминированной модели идентификации выполнения (SSOT)
-- Добавление полей ExecutionContext в таблицу outbox_events

ALTER TABLE outbox_events 
    ADD COLUMN IF NOT EXISTS schema_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS signal_id UUID,
    ADD COLUMN IF NOT EXISTS order_id UUID,
    ADD COLUMN IF NOT EXISTS execution_id UUID,
    ADD COLUMN IF NOT EXISTS causation_id UUID,
    ADD COLUMN IF NOT EXISTS correlation_id UUID;

-- Создание индексов для эффективного поиска по графу причинности и корреляции
CREATE INDEX IF NOT EXISTS idx_outbox_correlation_id ON outbox_events(correlation_id);
CREATE INDEX IF NOT EXISTS idx_outbox_causation_id ON outbox_events(causation_id);
CREATE INDEX IF NOT EXISTS idx_outbox_signal_id ON outbox_events(signal_id);
