package com.tradingbot.domain.strategy;

import com.tradingbot.domain.market.MarketSnapshot;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.position.PortfolioState;

import java.util.Optional;

/**
 * Strategy contract — pure function, no IO, no side effects.
 *
 * Rules (see PROMT.md):
 * - NO access to DB, HTTP, Execution layer, or Risk Engine
 * - Input: MarketSnapshot + PortfolioState (both immutable)
 * - Output: Optional<Signal> — empty means "no action"
 */
public interface TradingStrategy {

    /**
     * Produces a trading signal based solely on market data and current positions.
     *
     * @param snapshot immutable market snapshot (prices, candles, timestamp)
     * @param portfolio immutable current portfolio state (open positions)
     * @return Optional.of(signal) to act, Optional.empty() to pass
     */
    Optional<Signal> decide(MarketSnapshot snapshot, PortfolioState portfolio);

    /**
     * Unique strategy identifier used for routing and audit trail.
     */
    String strategyId();
}