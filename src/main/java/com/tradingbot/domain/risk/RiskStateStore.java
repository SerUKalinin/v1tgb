package com.tradingbot.domain.risk;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Thread-safe atomic storage for RiskState.
 * Uses compare-and-set for lock-free updates.
 */
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

@Component
public class RiskStateStore {

    private final AtomicReference<RiskState> globalState;
    private final Map<String, AtomicReference<RiskState>> symbolStates = new ConcurrentHashMap<>();
    private final RiskStateReducer reducer;

    public RiskStateStore(RiskStateReducer reducer) {
        this.globalState = new AtomicReference<>(RiskState.empty());
        this.reducer = reducer;
    }

    public RiskState getState() {
        return globalState.get();
    }

    public RiskState getSymbolState(String symbol) {
        return symbolStates.computeIfAbsent(symbol, k -> new AtomicReference<>(RiskState.empty())).get();
    }

    public RiskState update(RiskEvent event) {
        // Обновляем глобальное состояние
        globalState.updateAndGet(currentState -> reducer.reduce(currentState, event));
        
        // Если событие привязано к символу, обновляем и его сегмент
        if (event.getSymbol() != null) {
            return symbolStates.computeIfAbsent(event.getSymbol(), k -> new AtomicReference<>(RiskState.empty()))
                    .updateAndGet(currentState -> reducer.reduce(currentState, event));
        }
        
        return globalState.get();
    }

    public RiskState updateCustom(UnaryOperator<RiskState> updateFunction) {
        return globalState.updateAndGet(updateFunction);
    }
}
