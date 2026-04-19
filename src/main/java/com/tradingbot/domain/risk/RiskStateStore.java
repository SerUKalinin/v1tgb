package com.tradingbot.domain.risk;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe atomic store for the global RiskState.
 *
 * Uses AtomicReference for lock-free reads (hot path — called on every signal).
 * Writes happen only via RiskEngine — no external component may call updateInternal().
 */
@Component
public class RiskStateStore {

    private final AtomicReference<RiskState> globalState;

    public RiskStateStore() {
        this.globalState = new AtomicReference<>(RiskState.empty());
    }

    /**
     * Returns the current global risk state. Lock-free read.
     */
    public RiskState getState() {
        return globalState.get();
    }

    /**
     * Alias for getState() — kept for OMS compatibility.
     */
    public RiskState getCurrentState() {
        return globalState.get();
    }

    /**
     * Internal write — only RiskEngine may call this.
     * Protected access prevents accidental use from other layers.
     */
    protected void updateInternal(RiskState newState) {
        globalState.set(newState);
    }
}