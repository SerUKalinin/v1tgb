-- V1__baseline_schema.sql
-- Final SSOT Baseline Schema

-- 1. Users table
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    chat_id BIGINT NOT NULL UNIQUE,
    username VARCHAR(255),
    tier VARCHAR(50) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. Signals table
CREATE TABLE signals (
    id UUID PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL,
    type VARCHAR(20) NOT NULL,
    price DECIMAL(38, 18) NOT NULL,
    take_profit_1 DECIMAL(38, 18),
    take_profit_2 DECIMAL(38, 18),
    stop_loss DECIMAL(38, 18),
    strategy_id VARCHAR(255) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_signals_timestamp ON signals(timestamp);
CREATE INDEX idx_signals_strategy_id ON signals(strategy_id);

-- 3. Signal Claims table
CREATE TABLE signal_claims (
    signal_id UUID PRIMARY KEY,
    claimed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 4. Orders table
CREATE TABLE orders (
    id UUID PRIMARY KEY,
    client_order_id VARCHAR(255) NOT NULL UNIQUE,
    exchange_order_id VARCHAR(255),
    symbol VARCHAR(50) NOT NULL,
    side VARCHAR(20) NOT NULL,
    type VARCHAR(20) NOT NULL,
    quantity DECIMAL(38, 18) NOT NULL,
    price DECIMAL(38, 18),
    stop_loss DECIMAL(38, 18),
    take_profit DECIMAL(38, 18),
    strategy_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    average_price DECIMAL(38, 18),
    executed_quantity DECIMAL(38, 18) NOT NULL DEFAULT 0,
    signal_id UUID NOT NULL,
    execution_id UUID,
    execution_started_at TIMESTAMP WITH TIME ZONE,
    execution_attempts INT NOT NULL DEFAULT 0,
    rejection_reason TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_order_qty_positive CHECK (quantity > 0)
);
CREATE UNIQUE INDEX idx_orders_client_order_id ON orders(client_order_id);
CREATE INDEX idx_orders_strategy_id ON orders(strategy_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE UNIQUE INDEX idx_orders_signal_id ON orders(signal_id);

-- 5. Execution Claims table
CREATE TABLE execution_claims (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL UNIQUE,
    signal_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    claimed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_execution_claim_execution UNIQUE(execution_id),
    CONSTRAINT uq_execution_claim_signal UNIQUE(signal_id)
);

-- 6. Trades table
CREATE TABLE trades (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    client_order_id VARCHAR(255) NOT NULL,
    symbol VARCHAR(50) NOT NULL,
    side VARCHAR(20) NOT NULL,
    quantity DECIMAL(38, 18) NOT NULL,
    price DECIMAL(38, 18) NOT NULL,
    exchange_trade_id VARCHAR(255) UNIQUE,
    strategy_id VARCHAR(255),
    commission DECIMAL(38, 18),
    commission_asset VARCHAR(20),
    realized_pnl DECIMAL(38, 18),
    sequence_id BIGINT,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_trade_qty_positive CHECK (quantity > 0)
);
CREATE INDEX idx_trades_order_id ON trades(order_id);
CREATE INDEX idx_trades_client_order_id ON trades(client_order_id);
CREATE INDEX idx_trades_exchange_trade_id ON trades(exchange_trade_id);
CREATE INDEX idx_trades_symbol_executed ON trades(symbol, executed_at);

-- 7. Positions table
CREATE TABLE positions (
    id UUID PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL,
    strategy_id VARCHAR(255) NOT NULL,
    net_quantity DECIMAL(38, 18) NOT NULL,
    avg_entry_price DECIMAL(38, 18) NOT NULL,
    realized_pnl DECIMAL(38, 18),
    last_trade_id UUID,
    stop_loss DECIMAL(38, 18),
    take_profit DECIMAL(38, 18),
    status VARCHAR(50) NOT NULL DEFAULT 'OPEN',
    close_request_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_positions_symbol_strategy UNIQUE (symbol, strategy_id)
);
CREATE INDEX idx_positions_status ON positions(status);
CREATE INDEX idx_positions_last_trade_id ON positions(last_trade_id);

-- 8. Outbox events table
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    sequence_number BIGINT NOT NULL,
    payload TEXT NOT NULL,    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    retry_count INTEGER NOT NULL DEFAULT 0,
    attempt_count INT DEFAULT 0 NOT NULL,
    schema_version INT DEFAULT 1,
    last_error TEXT,
    lock_owner VARCHAR(255),
    locked_until TIMESTAMP WITH TIME ZONE,
    claimed_by VARCHAR(255),
    claimed_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    next_attempt_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    signal_id UUID,
    order_id UUID,
    execution_id UUID,
    causation_id UUID,
    correlation_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_outbox_unprocessed ON outbox_events(created_at) WHERE processed_at IS NULL;
CREATE INDEX idx_outbox_status_created ON outbox_events(status, created_at) WHERE status IN ('NEW', 'FAILED');
CREATE INDEX idx_outbox_claiming_v2 ON outbox_events (status, created_at) WHERE status IN ('NEW', 'FAILED', 'PROCESSING');
CREATE INDEX idx_outbox_aggregate_id ON outbox_events(aggregate_id);
CREATE INDEX idx_outbox_polling_v3 ON outbox_events (status, next_attempt_at) WHERE status IN ('PENDING', 'FAILED', 'PROCESSING');

-- 9. Risk events table
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

-- 10. Risk snapshots table
CREATE TABLE risk_snapshots (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(255) NOT NULL,
    last_version BIGINT NOT NULL,
    state_json JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (aggregate_id, last_version)
);

-- 11. Risk state snapshots table
CREATE TABLE risk_state_snapshots (
    id BIGSERIAL PRIMARY KEY,
    snapshot_data JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 12. Equity snapshots table
CREATE TABLE equity_snapshots (
    id BIGSERIAL PRIMARY KEY,
    strategy_id VARCHAR(255) NOT NULL,
    total_equity DECIMAL(38, 18) NOT NULL,
    available_balance DECIMAL(38, 18) NOT NULL,
    unrealized_pnl DECIMAL(38, 18) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_equity_snapshots_timestamp ON equity_snapshots(timestamp);

-- 13. Risk state table
CREATE TABLE risk_state (
    id VARCHAR(20) PRIMARY KEY,
    total_equity DECIMAL(38, 18) NOT NULL,
    available_balance DECIMAL(38, 18) NOT NULL,
    reserved_margin DECIMAL(38, 18) NOT NULL,
    halted BOOLEAN NOT NULL,
    version BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE,
    active_reservations TEXT,
    processed_event_ids TEXT,
    CONSTRAINT chk_risk_balance_positive CHECK (available_balance >= 0),
    CONSTRAINT chk_risk_reserved_limit CHECK (reserved_margin <= total_equity)
);

-- Seed initial risk core state
INSERT INTO risk_state (id, total_equity, available_balance, reserved_margin, halted, version, updated_at)
VALUES ('risk_core', 0.0, 0.0, 0.0, false, 0, CURRENT_TIMESTAMP);

-- 14. Risk Reservation Log table
CREATE TABLE risk_reservation_log (
    id UUID PRIMARY KEY,
    sequence_id BIGSERIAL,
    order_id UUID NOT NULL,
    client_order_id VARCHAR(255),
    event_type VARCHAR(20) NOT NULL,
    amount DECIMAL(38, 18) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 15. Execution Lock table
CREATE TABLE execution_lock (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    state VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 16. Processed events table (Idempotency)
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    consumer_name VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);