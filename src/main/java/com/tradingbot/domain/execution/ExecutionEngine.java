package com.tradingbot.domain.execution;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.OrderRequest;

public interface ExecutionEngine {
    ExecutionResult execute(OrderRequest request);
}