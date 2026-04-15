package com.tradingbot.domain.risk;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Thread-safe atomic storage for RiskState.
 * Uses compare-and-set for lock-free updates.
 */
import org.springframework.stereotype.Component;

@Component
public class RiskStateStore {

    private final AtomicReference<RiskState> stateReference;
    private final RiskStateReducer reducer;

    public RiskStateStore(RiskStateReducer reducer) {
        this.stateReference = new AtomicReference<>(RiskState.empty());
        this.reducer = reducer;
    }

    public RiskState getState() {
        return stateReference.get();
    }

    /**
     * Atomically updates the state by applying an event.
     * @param event The risk event to apply.
     * @return The new state after update.
     */
    public RiskState update(RiskEvent event) {
        return stateReference.updateAndGet(currentState -> reducer.reduce(currentState, event));
    }

    /**
     * Custom atomic update logic if needed.
     */
    public RiskState updateCustom(UnaryOperator<RiskState> updateFunction) {
        return stateReference.updateAndGet(updateFunction);
    }
}
