-- Add execution claims table to support the signal-level claim primitive
CREATE TABLE IF NOT EXISTS execution_claims (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    signal_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_execution_claims_signal_id UNIQUE (signal_id)
);
