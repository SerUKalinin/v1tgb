package com.tradingbot.domain.execution;

import java.util.UUID;

/**
 * Порт, позволяющий атомарно зарегистрировать попытку обработки сигнала.
 */
import java.util.UUID;

public interface ExecutionClaimPort {
    void claim(UUID signalId);
}
