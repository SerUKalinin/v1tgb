-- 1. Таблица пользователей (User.java)
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    chat_id BIGINT UNIQUE,
    username VARCHAR(255),
    tier VARCHAR(50),
    active BOOLEAN NOT NULL
);

-- 2. Таблица сигналов (SignalEntity.java)
CREATE TABLE signals (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(255),
    type VARCHAR(50),
    price DECIMAL(19, 8),
    take_profit1 DECIMAL(19, 8),
    take_profit2 DECIMAL(19, 8),
    stop_loss DECIMAL(19, 8),
    strategy_id VARCHAR(255),
    created_at TIMESTAMP,
    timestamp TIMESTAMP
);

-- 3. Таблица ордеров (OrderEntity.java)
CREATE TABLE orders (
    id VARCHAR(255) PRIMARY KEY,
    client_order_id VARCHAR(255) NOT NULL UNIQUE,
    symbol VARCHAR(255) NOT NULL,
    side VARCHAR(50) NOT NULL,
    type VARCHAR(50) NOT NULL,
    quantity DECIMAL(19, 8) NOT NULL,
    price DECIMAL(19, 8) NOT NULL,
    status VARCHAR(50) NOT NULL,
    execution_owner VARCHAR(255),
    execution_expires_at TIMESTAMP,
    strategy_id VARCHAR(255),
    risk_state_version BIGINT NOT NULL,
    version BIGINT,
    stop_loss DECIMAL(19, 8),
    take_profit DECIMAL(19, 8),
    exchange_order_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_orders_client_order_id ON orders(client_order_id);
CREATE INDEX idx_orders_status ON orders(status);

-- 4. Таблица сделок (TradeEntity.java)
CREATE TABLE trades (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(255),
    client_order_id VARCHAR(255) NOT NULL,
    symbol VARCHAR(255),
    side VARCHAR(50),
    quantity DECIMAL(19, 8),
    price DECIMAL(19, 8),
    external_trade_id VARCHAR(255),
    exchange_trade_id VARCHAR(255) UNIQUE,
    strategy_id VARCHAR(255),
    net_quantity DECIMAL(19, 8),
    avg_entry_price DECIMAL(19, 8),
    realized_pnl DECIMAL(19, 8),
    commission DECIMAL(19, 8),
    commission_asset VARCHAR(50),
    sequence_id BIGINT,
    executed_at TIMESTAMP,
    created_at TIMESTAMP
);
CREATE INDEX idx_trades_client_order_id ON trades(client_order_id);
CREATE INDEX idx_trades_exchange_trade_id ON trades(exchange_trade_id);
CREATE INDEX idx_trades_symbol_executed ON trades(symbol, executed_at);

-- 5. Таблица позиций (PositionEntity.java)
CREATE TABLE positions (
    symbol VARCHAR(255) PRIMARY KEY,
    strategy_id VARCHAR(255),
    net_quantity DECIMAL(19, 8),
    avg_entry_price DECIMAL(19, 8),
    realized_pnl DECIMAL(19, 8),
    last_trade_id BIGINT,
    stop_loss DECIMAL(19, 8),
    take_profit DECIMAL(19, 8),
    status VARCHAR(50),
    close_request_id UUID,
    version BIGINT,
    updated_at TIMESTAMP
);

-- 6. Таблица Outbox (OutboxEventEntity.java)
CREATE TABLE outbox_events (
    event_id VARCHAR(255) PRIMARY KEY,
    client_order_id VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP
);
CREATE INDEX idx_outbox_status ON outbox_events(status);
CREATE INDEX idx_outbox_created_at ON outbox_events(created_at);

-- 7. Идемпотентность исполнения (ExecutionIdempotencyEntity.java)
CREATE TABLE execution_idempotency (
    client_order_id VARCHAR(255) PRIMARY KEY,
    status VARCHAR(50),
    exchange_order_id VARCHAR(255),
    error_message TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 8. Снапшоты риска (RiskSnapshotEntity.java)
CREATE TABLE risk_snapshots (
    id BIGSERIAL PRIMARY KEY,
    state_json TEXT,
    version BIGINT,
    timestamp TIMESTAMP
);

-- 9. События риска (RiskEventEntity.java)
CREATE TABLE risk_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(255),
    data TEXT,
    created_at TIMESTAMP
);

-- 10. Снапшоты эквити (EquitySnapshotEntity.java)
CREATE TABLE equity_snapshots (
    id BIGSERIAL PRIMARY KEY,
    total_equity DECIMAL(19, 8),
    timestamp TIMESTAMP
);
