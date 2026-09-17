package com.tradingbot.domain.model;

import java.time.Instant;

/**
 * Port для восстановления застрявших outbox-событий.
 *
 * <p>
 * Application layer не должен знать о JPA Entity,
 * Spring Data Repository или OutboxStatus.
 *
 * <p>
 * Контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
public interface OutboxRecoveryPort {

    /**
     * Переводит все PROCESSING-события,
     * которые считаются зависшими, в FAILED.
     *
     * @param threshold момент, раньше которого событие считается stale
     * @return количество восстановленных событий
     */
    int resetStaleProcessingEvents(Instant threshold);
}