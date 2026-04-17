-- V7__add_risk_state_snapshots.sql
CREATE TABLE risk_state_snapshots (
    id BIGSERIAL PRIMARY KEY,
    timestamp TIMESTAMPTZ NOT NULL,
    balance DECIMAL(20, 8) NOT NULL,
    equity DECIMAL(20, 8) NOT NULL,
    daily_pnl DECIMAL(20, 8) NOT NULL,
    max_equity DECIMAL(20, 8) NOT NULL,
    processed_event_ids TEXT NOT NULL -- Храним как строку через запятую или JSON
);

CREATE INDEX idx_risk_snapshots_timestamp ON risk_state_snapshots(timestamp);
