-- V1__Initial_schema.sql
CREATE TABLE orders (
    id VARCHAR(255) PRIMARY KEY,
    client_order_id VARCHAR(255) UNIQUE,
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,
    quantity DECIMAL(20, 8) NOT NULL,
    price DECIMAL(20, 8),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE trades (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(255) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,
    quantity DECIMAL(20, 8) NOT NULL,
    price DECIMAL(20, 8) NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE positions (
    symbol VARCHAR(20) PRIMARY KEY,
    quantity DECIMAL(20, 8) NOT NULL,
    entry_price DECIMAL(20, 8) NOT NULL
);
