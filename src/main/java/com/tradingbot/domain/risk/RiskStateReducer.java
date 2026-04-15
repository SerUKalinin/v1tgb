package com.tradingbot.domain.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class RiskStateReducer {
    public RiskState reduce(RiskState currentState, RiskEvent event) {
        if (currentState.getProcessedEventIds().contains(event.getEventId())) {
            return currentState;
        }

        RiskState newState = switch (event) {
            case RiskEvent.TradeExecuted e -> handleTradeExecuted(currentState, e);
            case RiskEvent.PriceUpdated e -> handlePriceUpdated(currentState, e);
            default -> currentState;
        };

        java.util.Set<String> newEventIds = new java.util.HashSet<>(newState.getProcessedEventIds());
        newEventIds.add(event.getEventId());
        
        return newState.toBuilder()
                .processedEventIds(java.util.Set.copyOf(newEventIds))
                .build();
    }
    private RiskState handleTradeExecuted(RiskState state, RiskEvent.TradeExecuted event) {
        BigDecimal newDailyPnl = state.getDailyPnl().add(event.realizedPnl());
        BigDecimal newEquity = state.getTotalEquity().add(event.realizedPnl());
        
        Map<String, BigDecimal> newExposures = new HashMap<>(state.getSymbolExposures());
        BigDecimal currentExp = newExposures.getOrDefault(event.symbol(), BigDecimal.ZERO);
        newExposures.put(event.symbol(), currentExp.add(event.quantity().multiply(event.price())));

        return state.toBuilder()
                .dailyPnl(newDailyPnl)
                .totalEquity(newEquity)
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
