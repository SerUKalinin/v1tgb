CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    chat_id BIGINT NOT NULL UNIQUE,
    username VARCHAR(255),
    tier VARCHAR(50) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

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
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_orders_client_order_id ON orders(client_order_id);
CREATE INDEX idx_orders_strategy_id ON orders(strategy_id);

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
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);CREATE INDEX idx_trades_order_id ON trades(order_id);
CREATE INDEX idx_trades_client_order_id ON trades(client_order_id);
CREATE INDEX idx_trades_exchange_trade_id ON trades(exchange_trade_id);
CREATE INDEX idx_trades_symbol_executed ON trades(symbol, executed_at);

CREATE TABLE positions (
    id UUID PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL,
    strategy_id VARCHAR(255) NOT NULL,
    net_quantity DECIMAL(38, 18) NOT NULL,
    avg_entry_price DECIMAL(38, 18) NOT NULL,
    realized_pnl DECIMAL(38, 18),
    last_trade_id UUID,
    stop_loss DECIMAL(38, 18),    take_profit DECIMAL(38, 18),
    status VARCHAR(50) NOT NULL,
    close_request_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (symbol, strategy_id)
);
CREATE TABLE signals (
    id BIGSERIAL PRIMARY KEY,
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

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_outbox_unprocessed ON outbox_events(created_at) WHERE processed_at IS NULL;
CREATE INDEX idx_outbox_processed_created ON outbox_events(processed_at, created_at);
