package com.tradingbot.application.bootstrap;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SystemStateManager {

    public enum SystemState {
        INITIALIZING,
        RISK_RECOVERING,
        COLD_START_RECONCILIATION,
        RECONCILING,
        MARKET_WARMING,
        READY,
        TRADING_ENABLED,
        HALTED
    }

    @Getter
    private volatile SystemState state = SystemState.INITIALIZING;

    public void updateState(SystemState newState) {
        log.info("[SYSTEM-STATE] Transition: {} -> {}", this.state, newState);
        this.state = newState;
    }

    public boolean isReady() {
        return state == SystemState.READY || state == SystemState.TRADING_ENABLED;
    }

    public boolean isColdStart() {
        return state == SystemState.COLD_START_RECONCILIATION;
    }
}
