package com.tradingbot.application.risk;

import java.util.concurrent.atomic.AtomicReference;
import com.tradingbot.domain.risk.RiskState;

/**
 * Потокобезопасное хранилище актуального {@link RiskState}.
 * <p>
 * Использует lock-free механизм обновления через {@link AtomicReference}.
 * Предназначено для кэширования состояния риска в памяти приложения.
 * <p>
 * Гарантирует быстрый доступ к последнему известному состоянию risk-системы
 * без обращения к внешнему хранилищу.
 */
public class RiskStateStore {

    private final AtomicReference<RiskState> globalCache = new AtomicReference<>(RiskState.empty());

    /**
     * Возвращает текущее закэшированное состояние риска.
     *
     * @return актуальный {@link RiskState}
     */
    public RiskState getState() {
        return globalCache.get();
    }

    /**
     * Обновляет кэш состояния риска.
     * <p>
     * Должен вызываться только после успешного коммита состояния в БД.
     *
     * @param newState новое состояние риска
     */
    public void updateCache(RiskState newState) {
        globalCache.set(newState);
    }

    /**
     * Внутреннее обновление состояния риска.
     * <p>
     * Делегирует вызов в {@link #updateCache(RiskState)}.
     *
     * @param newState новое состояние риска
     */
    public void updateInternal(RiskState newState) {
        updateCache(newState);
    }

    /**
     * Полная очистка кэша состояния риска.
     * <p>
     * Сбрасывает состояние до {@link RiskState#empty()}.
     */
    public void clearCache() {
        globalCache.set(RiskState.empty());
    }
}