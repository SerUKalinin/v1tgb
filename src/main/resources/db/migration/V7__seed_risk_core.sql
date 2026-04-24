-- Seed initial risk core state if not exists
-- Compatible with PostgreSQL and H2
INSERT INTO risk_state (id, total_equity, available_balance, reserved_margin, halted, version, updated_at)
SELECT 'risk_core', 0, 0, 0, false, 0, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM risk_state WHERE id = 'risk_core'
);
