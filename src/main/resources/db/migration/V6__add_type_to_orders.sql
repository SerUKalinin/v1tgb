-- Migration to add missing columns for Risk Engine and Ledger with existence checks
DO $$ 
BEGIN 
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='orders' AND column_name='type') THEN
        ALTER TABLE orders ADD COLUMN type VARCHAR(50) NOT NULL DEFAULT 'MARKET';
    END IF;
END $$;

DO $$ 
BEGIN 
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='trades' AND column_name='net_quantity') THEN
        ALTER TABLE trades ADD COLUMN net_quantity DECIMAL(38, 18);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='trades' AND column_name='avg_entry_price') THEN
        ALTER TABLE trades ADD COLUMN avg_entry_price DECIMAL(38, 18);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='trades' AND column_name='realized_pnl') THEN
        ALTER TABLE trades ADD COLUMN realized_pnl DECIMAL(38, 18);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='trades' AND column_name='commission') THEN
        ALTER TABLE trades ADD COLUMN commission DECIMAL(38, 18);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='trades' AND column_name='commission_asset') THEN
        ALTER TABLE trades ADD COLUMN commission_asset VARCHAR(20);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='trades' AND column_name='strategy_id') THEN
        ALTER TABLE trades ADD COLUMN strategy_id VARCHAR(100);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='trades' AND column_name='external_trade_id') THEN
        ALTER TABLE trades ADD COLUMN external_trade_id VARCHAR(100);
    END IF;
END $$;
