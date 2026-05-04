-- Migration: Add sequence_id to risk_reservation_log
ALTER TABLE risk_reservation_log ADD COLUMN sequence_id BIGSERIAL NOT NULL;
CREATE UNIQUE INDEX idx_risk_reservation_sequence_id ON risk_reservation_log(sequence_id);
