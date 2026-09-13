package com.tradingbot.domain.risk;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Результат принятого решения риск-менеджера.
 * <p>
 * Представляет финальный outcome проверки риска для конкретного сигнала,
 * включая идентификаторы трассировки и состояние обработки.
 */
@Value
@Builder(toBuilder = true)
public class RiskDecisionResult {

    /**
     * Тип принятого решения риск-менеджера.
     */
    RiskDecisionType decision;

    /**
     * Причина принятого решения.
     */
    String reason;

    /**
     * Идентификатор торгового сигнала.
     */
    String signalId;

    /**
     * Идентификатор трассировки запроса (trace correlation id).
     */
    String traceId;

    /**
     * Текущее состояние обработки сигнала.
     */
    String state;

    /**
     * Временная метка формирования решения.
     */
    Instant timestamp;
}