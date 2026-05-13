package com.tradingbot.domain.execution;

import com.tradingbot.tracing.ExecutionContext;

import java.util.UUID;

public interface ExecutionClaimPort {
    boolean existsBySignalId(UUID signalId);
    boolean existsByExecutionId(UUID executionId);
    void claimSignal(UUID signalId);
    void claimExecution(UUID executionId);}