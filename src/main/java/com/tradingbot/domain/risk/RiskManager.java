package com.tradingbot.domain.risk;

import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.Order;

import com.tradingbot.tracing.ExecutionContext;
import java.util.Optional;

public interface RiskManager {
    Optional<Order> evaluateAndReserve(ExecutionContext context, SignalEvent signal);
    Optional<Order> approveSignal(SignalEvent signal);
    RiskDecision check(Order order);
    RiskDecision evaluate(com.tradingbot.domain.model.Signal signal);
    boolean isApprovalFresh(Order order);
}