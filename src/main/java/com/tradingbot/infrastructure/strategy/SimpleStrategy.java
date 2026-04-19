package com.tradingbot.infrastructure.strategy;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.market.MarketSnapshot;
import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.position.PortfolioState;
import com.tradingbot.domain.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal test strategy: buys when last close > prev close, sells otherwise.
 *
 * Pure function — no DB, no HTTP, no Risk, no Execution access.
 */
@Component
@Slf4j
public class SimpleStrategy implements TradingStrategy {

    private static final String STRATEGY_ID = "simple-v1";
    private static final int MIN_CANDLES = 2;

    @Override
    public String strategyId() {
        return STRATEGY_ID;
    }

    @Override
    public Optional<Signal> decide(MarketSnapshot snapshot, PortfolioState portfolio) {
        List<Candle> candles = snapshot.getCandles();

        if (candles == null || candles.size() < MIN_CANDLES) {
            log.debug("[{}] Not enough candles for {}: {}", STRATEGY_ID, snapshot.getSymbol(),
                    candles == null ? 0 : candles.size());
            return Optional.empty();
        }

        BigDecimal currentClose = candles.get(candles.size() - 1).getClose();
        BigDecimal prevClose    = candles.get(candles.size() - 2).getClose();

        if (currentClose == null || prevClose == null
                || prevClose.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("[{}] Invalid price data for {}", STRATEGY_ID, snapshot.getSymbol());
            return Optional.empty();
        }

        OrderSide side = currentClose.compareTo(prevClose) > 0 ? OrderSide.BUY : OrderSide.SELL;

        Signal signal = Signal.builder()
                .clientOrderId(UUID.randomUUID().toString())
                .symbol(snapshot.getSymbol())
                .side(side)
                .price(currentClose)
                .quantity(null) // Risk Engine will size the position
                .strategyId(STRATEGY_ID)
                .generatedAt(Instant.now())
                .build();

        log.info("[{}] Signal for {}: {} (current={}, prev={})",
                STRATEGY_ID, snapshot.getSymbol(), side, currentClose, prevClose);

        return Optional.of(signal);
    }
}