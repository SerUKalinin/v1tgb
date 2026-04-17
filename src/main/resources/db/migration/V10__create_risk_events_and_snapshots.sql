-- V8__create_risk_events_and_snapshots.sql
CREATE TABLE risk_events (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID UNIQUE NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_risk_events_aggregate_version UNIQUE (aggregate_id, version)
);

CREATE INDEX idx_risk_events_aggregate_version ON risk_events(aggregate_id, version);
CREATE INDEX idx_risk_events_event_id ON risk_events(event_id);

CREATE TABLE risk_snapshots (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(255) NOT NULL,
    last_version BIGINT NOT NULL,
    state_json JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_risk_snapshots_aggregate_version UNIQUE (aggregate_id, last_version)
);

CREATE INDEX idx_risk_snapshots_aggregate_version ON risk_snapshots(aggregate_id, last_version);
