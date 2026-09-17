package com.tradingbot.domain.model;

import com.tradingbot.domain.event.SignalEvent;

/**
 * Порт сохранения торгового сигнала.
 *
 * Application/domain не должны знать о JPA Entity и Spring Data Repository.
 *
 * Контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
public interface SignalPort {

    void save(SignalEvent signal);
}