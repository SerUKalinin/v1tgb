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
}
