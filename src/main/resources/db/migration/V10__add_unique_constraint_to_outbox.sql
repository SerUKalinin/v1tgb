-- Migration to add unique constraint on aggregate_id and sequence_number for outbox_events
ALTER TABLE outbox_events
ADD CONSTRAINT uq_outbox_aggregate_seq
UNIQUE (aggregate_id, sequence_number);
