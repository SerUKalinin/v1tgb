-- Migration to add execution_attempts column to orders table
ALTER TABLE orders ADD COLUMN execution_attempts INTEGER DEFAULT 0 NOT NULL;
