package com.tradingbot.domain.risk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RiskEngine — stateless processor + state lifecycle manager.
 *
 * Rules:
 * - No IO, no side effects in process()
 * - State is owned by RiskStateStore (AtomicReference)
 * - initialize() is called once on startup by RiskStateRecoveryService
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RiskEngine {

    private final RiskStateReducer reducer;
    private final RiskStateStore riskStateStore;

    /**
     * Apply a RiskEvent to current state and store the result.
     * Called after every trade execution and price update.
     */
    public RiskState process(RiskState state, RiskEvent event) {
        if (state.isHalted() && !(event instanceof RiskEvent.TradingHalted)) {
            log.warn("[RiskEngine] HALTED — ignoring event: {}", event.getEventId());
            return state;
        }

        RiskState newState = reducer.reduce(state, event);

        // Persist updated state
        riskStateStore.updateInternal(newState);

        if (newState.isHalted() && !state.isHalted()) {
            log.error("[RiskEngine] System transitioned to HALTED at version {}", newState.getVersion());
        }

        return newState;
    }

    /**
     * Returns current risk state snapshot (thread-safe read).
     */
    public RiskState getState() {
        return riskStateStore.getState();
    }

    /**
     * Called once on startup by RiskStateRecoveryService after event replay.
     * Must not be called from any other component.
     */
    public void initialize(RiskState recoveredState) {
        log.info("[RiskEngine] Initializing with recovered state: version={}, halted={}, equity={}",
                recoveredState.getVersion(),
                recoveredState.isHalted(),
                recoveredState.getTotalEquity());
        riskStateStore.updateInternal(recoveredState);
    }

    /**
     * Delegates to store for internal updates — only RiskEngine may call this.
     */
    public void updateState(RiskState newState) {
        riskStateStore.updateInternal(newState);
    }
}