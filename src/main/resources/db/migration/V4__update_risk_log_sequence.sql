-- sequence_id defines global deterministic ordering of financial events
CREATE SEQUENCE IF NOT EXISTS risk_reservation_seq START 1 INCREMENT 1;

ALTER TABLE risk_reservation_log ALTER COLUMN sequence_id DROP DEFAULT;
ALTER TABLE risk_reservation_log ALTER COLUMN sequence_id SET NOT NULL;
