package com.tradingbot.domain.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import java.math.RoundingMode;

import static com.tradingbot.domain.risk.RiskState.safeAdd;
import static com.tradingbot.domain.risk.RiskState.safeCompare;

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
                case RiskEvent.CapitalReserved e -> handleCapitalReserved(currentState, e);
                case RiskEvent.CapitalReleased e -> handleCapitalReleased(currentState, e);
                default -> currentState;
            };

            // 2. Financial Invariant Guard
            newState.validateInvariants();

            // Auto-Halt Logic
            if (!newState.isHalted()) {                // 1. Daily Loss Limit > 5%
                BigDecimal dailyLossLimit = newState.getTotalEquity().multiply(new BigDecimal("0.05"));
                if (safeCompare(newState.getDailyPnl(), dailyLossLimit.negate()) < 0) {
                    log.error("[RiskReducer] AUTO-HALT: Daily loss limit exceeded (5%)");
                    newState.setHalted(true);
                } else {
                    // 2. Max Drawdown > 10% (Computed from peak)
                    BigDecimal currentDrawdown = calculateDrawdown(newState);
                    if (safeCompare(currentDrawdown, new BigDecimal("10.0")) > 0) {
                        log.error("[RiskReducer] AUTO-HALT: Max drawdown exceeded (10%). Current DD: {}%", currentDrawdown);
                        newState.setHalted(true);
                    }
                }
            }

            java.util.Set<String> newEventIds = new java.util.HashSet<>(newState.getProcessedEventIds());
            newEventIds.add(event.getEventId());
            newState.setProcessedEventIds(java.util.Set.copyOf(newEventIds));
            
            return newState;
        } catch (Exception e) {
            log.error("CRITICAL: RiskStateReducer failed for event {}. Forcing AUTO-HALT.", 
                    event.getEventId(), e);
            
            currentState.setHalted(true);
            currentState.setLastError(e.getMessage());
            return currentState;
        }
    }    private BigDecimal calculateDrawdown(RiskState state) {
        if (safeCompare(state.getMaxEquity(), BigDecimal.ZERO) <= 0) {
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

    private RiskState handleCapitalReserved(RiskState state, RiskEvent.CapitalReserved event) {
        log.info("[RiskReducer] Reserving {} for order {}", event.amount(), event.orderId());
        
        return state.toBuilder()
                .balance(state.getBalance().subtract(event.amount()))
                .reserved(state.getReserved().add(event.amount()))
                .lastUpdateTimestamp(event.timestamp())
                .build();
    }

    private RiskState handleCapitalReleased(RiskState state, RiskEvent.CapitalReleased event) {
        log.info("[RiskReducer] Releasing {} for order {} (Reason: {})", event.amount(), event.orderId(), event.reason());
        
        return state.toBuilder()
                .balance(state.getBalance().add(event.amount()))
                .reserved(state.getReserved().subtract(event.amount()))
                .lastUpdateTimestamp(event.timestamp())
                .build();
    }
}
