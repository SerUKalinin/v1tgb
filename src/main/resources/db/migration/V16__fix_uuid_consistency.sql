-- Изменение типа signal_id в таблице execution_claims
ALTER TABLE execution_claims ALTER COLUMN signal_id DROP DEFAULT;
ALTER TABLE execution_claims ALTER COLUMN signal_id TYPE UUID USING signal_id::UUID;

-- Исправление для таблицы orders
DO $$ 
BEGIN 
    IF EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'orders' AND column_name = 'signal_id' AND data_type = 'character varying'
    ) THEN
        ALTER TABLE orders ALTER COLUMN signal_id DROP DEFAULT;
        ALTER TABLE orders ALTER COLUMN signal_id TYPE UUID USING signal_id::UUID;
    END IF;
END $$;
