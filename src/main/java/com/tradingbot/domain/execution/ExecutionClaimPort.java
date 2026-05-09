package com.tradingbot.domain.execution;

/**
 * Порт, позволяющий атомарно зарегистрировать попытку обработки сигнала.
 */
public interface ExecutionClaimPort {

    /**
     * Пытается заявить сигнал к исполнению.
     *
     * @param signalId идентификатор сигнала
     * @throws AlreadyClaimedException если сигнал уже заблокирован другой обработкой
     */
    void claim(String signalId);
}
