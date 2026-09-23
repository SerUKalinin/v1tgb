package com.tradingbot.domain.execution;

import java.util.UUID;

/**
 * Порт захвата (claim) торговых сигналов.
 *
 * <p>Используется для обеспечения идемпотентной обработки сигналов.</p>
 *
 * <p>Критическое требование:
 * claim должен участвовать в транзакции canonical signal pipeline.</p>
 */
public interface SignalClaimPort {

    /**
     * Проверяет, был ли уже захвачен указанный сигнал.
     *
     * @param signalId идентификатор торгового сигнала
     * @return true, если сигнал уже находится в обработанном/claimed состоянии
     */
    boolean exists(UUID signalId);

    /**
     * Захватывает сигнал для дальнейшей обработки.
     *
     * <p>Операция должна выполняться в существующей transaction
     * signal pipeline. Нельзя использовать отдельный REQUIRES_NEW claim,
     * иначе claim может успешно закоммититься при последующем rollback
     * создания Order.</p>
     *
     * @param signalId идентификатор торгового сигнала
     */
    void claim(UUID signalId);
}