CREATE TABLE execution_lock (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    state VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
