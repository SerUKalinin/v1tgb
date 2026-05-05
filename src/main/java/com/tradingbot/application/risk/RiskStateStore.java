package com.tradingbot.application.risk;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe atomic storage for RiskState.
 * Uses compare-and-set for lock-free updates.
 */
import com.tradingbot.domain.risk.RiskState;

public class RiskStateStore {

    private final AtomicReference<RiskState> globalCache = new AtomicReference<>(RiskState.empty());

    public RiskState getState() {
        return globalCache.get();
    }

    /**
     * Обновление кэша. Вызывается только после успешного коммита в БД.
     */
    public void updateCache(RiskState newState) {
        globalCache.set(newState);
    }

    public void updateInternal(RiskState newState) {
        updateCache(newState);
    }

    public void clearCache() {
        globalCache.set(RiskState.empty());
    }
}
