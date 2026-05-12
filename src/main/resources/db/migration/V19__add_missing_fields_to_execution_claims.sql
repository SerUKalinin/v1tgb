-- Add missing columns to execution_claims table
ALTER TABLE execution_claims 
    ADD COLUMN IF NOT EXISTS execution_id UUID,
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP;

-- Update signal_id to UUID if it's still varchar (for consistency with entity)
-- Note: This assumes existing data can be cast to UUID or table is empty
ALTER TABLE execution_claims 
    ALTER COLUMN signal_id TYPE UUID USING signal_id::UUID;

-- Ensure execution_id is NOT NULL if we want to match entity's nullable=false
-- But first we might need to handle existing rows if any
ALTER TABLE execution_claims 
    ALTER COLUMN execution_id SET NOT NULL;
