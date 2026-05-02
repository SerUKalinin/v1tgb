-- Migration: Create risk_reservation_log table
CREATE TABLE risk_reservation_log (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    client_order_id VARCHAR(255),
    event_type VARCHAR(20) NOT NULL,
    amount DECIMAL(38,18) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_risk_reservation_order_id ON risk_reservation_log(order_id);
CREATE INDEX idx_risk_reservation_created_at ON risk_reservation_log(created_at);
