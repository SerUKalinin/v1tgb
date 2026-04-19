package com.tradingbot.domain.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class RiskStateReducer {

    public RiskState reduce(RiskState currentState, RiskEvent event) {
        if (currentState.getProcessedEventIds().contains(event.getEventId())) {
            return currentState;
        }

        try {
            RiskState newState = switch (event) {
                case RiskEvent.TradeExecuted e -> handleTradeExecuted(currentState, e);
                case RiskEvent.PriceUpdated e -> handlePriceUpdated(currentState, e);
                case RiskEvent.TradingHalted e -> handleTradingHalted(currentState, e);
                default -> currentState;
            };

            if (!newState.isHalted()) {
                BigDecimal dailyLossLimit = newState.getTotalEquity().multiply(new BigDecimal("0.05"));
                if (newState.getDailyPnl().compareTo(dailyLossLimit.negate()) < 0) {
                    log.error("[RiskReducer] AUTO-HALT: Daily loss limit exceeded (5%)");
                    newState = newState.toBuilder().halted(true).build();
                } else {
                    BigDecimal currentDrawdown = calculateDrawdown(newState);
                    if (currentDrawdown.compareTo(new BigDecimal("10.0")) > 0) {
                        log.error("[RiskReducer] AUTO-HALT: Max drawdown exceeded (10%). Current DD: {}%", currentDrawdown);
                        newState = newState.toBuilder().halted(true).build();
                    }
                }
            }

            java.util.LinkedHashSet<String> newEventIds = new java.util.LinkedHashSet<>(newState.getProcessedEventIds());
            newEventIds.add(event.getEventId());
            
            // Ограничение размера до 10 000 (LRU)
            if (newEventIds.size() > 10000) {
                String oldest = newEventIds.iterator().next();
                newEventIds.remove(oldest);
            }
            
            return newState.toBuilder()
                    .processedEventIds(java.util.Collections.unmodifiableSet(newEventIds))
                    .version(currentState.getVersion() + 1)
                    .build();
        } catch (Exception e) {            log.error("Critical error in RiskStateReducer for event {}: {}.", event.getEventId(), e.getMessage());
            return currentState;
        }
    }

    private BigDecimal calculateDrawdown(RiskState state) {
        if (state.getMaxEquity() == null || state.getMaxEquity().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return state.getMaxEquity()
                .subtract(state.getTotalEquity())
                .divide(state.getMaxEquity(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private RiskState handleTradingHalted(RiskState state, RiskEvent.TradingHalted event) {
        log.warn("Risk Engine HALTED: {}", event.reason());
        return state.toBuilder()
                .halted(true)
                .lastUpdateTimestamp(event.timestamp())
                .build();
    }

    private RiskState handleTradeExecuted(RiskState state, RiskEvent.TradeExecuted event) {
        BigDecimal newDailyPnl = state.getDailyPnl().add(event.realizedPnl());
        BigDecimal newEquity = state.getTotalEquity().add(event.realizedPnl());
        BigDecimal newMaxEquity = newEquity.max(state.getMaxEquity());
        
        Map<String, BigDecimal> newExposures = new HashMap<>(state.getSymbolExposures());
        BigDecimal currentExp = newExposures.getOrDefault(event.symbol(), BigDecimal.ZERO);
        newExposures.put(event.symbol(), currentExp.add(event.quantity().multiply(event.price())));

        Map<String, Instant> newCooldowns = new HashMap<>(state.getCooldowns());
        newCooldowns.put(event.symbol(), event.timestamp().plusSeconds(60));

        return state.toBuilder()
                .dailyPnl(newDailyPnl)
                .totalEquity(newEquity)
                .maxEquity(newMaxEquity)
                .symbolExposures(Map.copyOf(newExposures))
                .cooldowns(Map.copyOf(newCooldowns))
                .lastUpdateTimestamp(event.timestamp())
                .build();
    }

    private RiskState handlePriceUpdated(RiskState state, RiskEvent.PriceUpdated event) {
        return state.toBuilder()
                .lastUpdateTimestamp(event.timestamp())
                .build();
    }
}
