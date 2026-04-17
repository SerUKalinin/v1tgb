-- V11__add_missing_columns_to_signals.sql

ALTER TABLE signals
    ADD COLUMN stop_loss DECIMAL(20, 8);

ALTER TABLE signals
    ADD COLUMN take_profit1 DECIMAL(20, 8);

ALTER TABLE signals
    ADD COLUMN take_profit2 DECIMAL(20, 8);

ALTER TABLE signals
    ADD COLUMN timestamp TIMESTAMP WITH TIME ZONE;