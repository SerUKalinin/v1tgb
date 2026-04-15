package com.tradingbot.domain.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import java.math.RoundingMode;

@Component
@Slf4j
public class RiskStateReducer {
    public RiskState reduce(RiskState currentState, RiskEvent event) {
        // 1. Idempotency check inside reducer to ensure purity during replay
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

            // Auto-Halt Logic
            if (!newState.isHalted()) {
                // 1. Daily Loss Limit > 5%
                BigDecimal dailyLossLimit = newState.getTotalEquity().multiply(new BigDecimal("0.05"));
                if (newState.getDailyPnl().compareTo(dailyLossLimit.negate()) < 0) {
                    log.error("[RiskReducer] AUTO-HALT: Daily loss limit exceeded (5%)");
                    newState = newState.toBuilder().halted(true).build();
                } else {
                    // 2. Max Drawdown > 10% (Computed from peak)
                    BigDecimal currentDrawdown = calculateDrawdown(newState);
                    if (currentDrawdown.compareTo(new BigDecimal("10.0")) > 0) {
                        log.error("[RiskReducer] AUTO-HALT: Max drawdown exceeded (10%). Current DD: {}%", currentDrawdown);
                        newState = newState.toBuilder().halted(true).build();
                    }
                }
            }

            java.util.Set<String> newEventIds = new java.util.HashSet<>(newState.getProcessedEventIds());
            newEventIds.add(event.getEventId());
            
            return newState.toBuilder()
                    .processedEventIds(java.util.Set.copyOf(newEventIds))
                    .version(currentState.getVersion() + 1)
                    .build();
        } catch (Exception e) {
            log.error("Critical error in RiskStateReducer for event {}: {}. Returning current state to preserve consistency.", 
                    event.getEventId(), e.getMessage());
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

        return state.toBuilder()
                .dailyPnl(newDailyPnl)
                .totalEquity(newEquity)
                .maxEquity(newMaxEquity)
                .symbolExposures(Map.copyOf(newExposures))
                .lastUpdateTimestamp(event.timestamp())
                .build();
    }

    private RiskState handlePriceUpdated(RiskState state, RiskEvent.PriceUpdated event) {
        // В данной реализации обновляем только метку времени, 
        // расчет нереализованного PnL может быть добавлен здесь
        return state.toBuilder()
                .lastUpdateTimestamp(event.timestamp())
                .build();
    }
}
