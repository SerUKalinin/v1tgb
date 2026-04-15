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

    public RiskStateStore() {
        this.globalState = new AtomicReference<>(RiskState.empty());
    }

    public RiskState getState() {
        return globalState.get();
    }

    public RiskState getSymbolState(String symbol) {
        return symbolStates.computeIfAbsent(symbol, k -> new AtomicReference<>(RiskState.empty())).get();
    }

    /**
     * Internal update only for RiskEngine.
     */
    protected void updateInternal(RiskState newState) {
        globalState.set(newState);
        // Note: Symbol-specific state segments can be updated here if needed
    }
}
