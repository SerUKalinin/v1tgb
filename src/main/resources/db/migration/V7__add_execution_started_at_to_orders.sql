-- Migration to add execution_started_at column to orders table
ALTER TABLE orders ADD COLUMN execution_started_at TIMESTAMP WITH TIME ZONE;
