package com.tradingbot.domain.risk;

import java.util.UUID;

/**
 * Порт доступа к состоянию risk-engine.
 *
 * <p>Определяет операции чтения, сохранения состояния риска,
 * а также поддержку идемпотентной обработки событий.</p>
 *
 * <p>Реализация может быть основана на БД, Redis или in-memory storage.</p>
 */
public interface RiskStatePort {

    /**
     * Получить текущее состояние risk-engine.
     */
    RiskState get();

    /**
     * Сохранить новое состояние risk-engine.
     */
    void save(RiskState state);

    /**
     * Зафиксировать обработку события и обновить состояние.
     *
     * <p>Используется для обеспечения идемпотентности обработки RiskEvent.</p>
     */
    void markEventProcessed(UUID id, RiskState state, RiskEvent event);

    /**
     * Проверить, было ли событие уже обработано.
     */
    boolean isEventProcessed(UUID id);
}