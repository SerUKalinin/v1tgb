package com.tradingbot.domain.execution;

import java.util.UUID;

/**
 * Порт управления захватом (claim) сигналов и execution-идентификаторов.
 * <p>
 * Используется для обеспечения идемпотентности и защиты от повторного выполнения
 * одного и того же сигнала или execution в распределённой системе исполнения.
 * Реализация обычно опирается на БД-уникальность или атомарные операции.
 */
public interface ExecutionClaimPort {

    /**
     * Проверяет, был ли уже захвачен сигнал.
     *
     * @param signalId идентификатор торгового сигнала
     * @return true, если сигнал уже был захвачен, иначе false
     */
    boolean existsBySignalId(UUID signalId);

    /**
     * Проверяет, был ли уже захвачен execution.
     *
     * @param executionId идентификатор исполнения
     * @return true, если execution уже был захвачен, иначе false
     */
    boolean existsByExecutionId(UUID executionId);

    /**
     * Захватывает сигнал для обработки.
     *
     * @param signalId идентификатор торгового сигнала
     */
    void claimSignal(UUID signalId);

    /**
     * Захватывает execution и связывает его с сигналом.
     *
     * @param executionId идентификатор исполнения
     * @param signalId идентификатор торгового сигнала
     */
    void claimExecution(UUID executionId, UUID signalId);
}