CREATE TABLE risk_events (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_id VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (aggregate_id, version)
);

CREATE INDEX idx_risk_events_aggregate ON risk_events(aggregate_id);

CREATE TABLE risk_snapshots (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(255) NOT NULL,
    last_version BIGINT NOT NULL,
    state_json JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (aggregate_id, last_version)
);

CREATE TABLE risk_state_snapshots (
    id BIGSERIAL PRIMARY KEY,
    snapshot_data JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE equity_snapshots (
    id BIGSERIAL PRIMARY KEY,
    strategy_id VARCHAR(255) NOT NULL,
    total_equity DECIMAL(38, 18) NOT NULL,
    available_balance DECIMAL(38, 18) NOT NULL,
    unrealized_pnl DECIMAL(38, 18) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_equity_snapshots_timestamp ON equity_snapshots(timestamp);

CREATE TABLE risk_state (
    id VARCHAR(255) PRIMARY KEY,
    total_equity DECIMAL(38, 18),
    available_balance DECIMAL(38, 18),
    reserved_margin DECIMAL(38, 18),
    halted BOOLEAN,
    version BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE
);
