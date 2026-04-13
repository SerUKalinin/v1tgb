package com.tradingbot.domain.execution;

import com.tradingbot.domain.model.Order;

public interface ExecutionEngine {
    ExecutionResult execute(Order order);
}