-- V10__Harden_outbox_claiming.sql
-- Добавление полей для распределенного захвата событий Outbox
ALTER TABLE outbox_events ADD COLUMN lock_owner VARCHAR(255);
ALTER TABLE outbox_events ADD COLUMN locked_until TIMESTAMP;
ALTER TABLE outbox_events ADD COLUMN attempt_count INT DEFAULT 0 NOT NULL;

-- Индекс для оптимизации выборки NEW/FAILED и просроченных PROCESSING событий
-- Используется в OutboxEventRepository.claimBatchWithLock
CREATE INDEX idx_outbox_claiming_v2 ON outbox_events (status, created_at) 
WHERE status IN ('NEW', 'FAILED', 'PROCESSING');
