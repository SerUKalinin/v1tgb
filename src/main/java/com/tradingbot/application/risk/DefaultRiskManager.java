package com.tradingbot.application.risk;

import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Optional;

/**
 * Implementation of RiskManager that acts as the Enforcement Gate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultRiskManager implements RiskManager {

    private final RiskService riskService;
    private final RiskEngine riskEngine;
    private final ExecutionLogger executionLogger;

    @Override
    public Optional<Order> evaluateAndReserve(ExecutionContext context, SignalEvent signal) {
        return riskEngine.evaluateSignal(context, signal);
    }

    @Override
    public Optional<Order> approveSignal(SignalEvent signal) {
        return riskEngine.evaluateSignal(signal.getExecutionContext(), signal);
    }    @Override
    public RiskDecision check(Order order) {
        RiskState currentState = riskService.getState();
        if (currentState.isHalted()) {
            return RiskDecision.reject(
                    RiskDecision.Reason.HALTED,
                    "System is HALTED",
                    Collections.singletonList("Halt check")
            );
        }
        return RiskDecision.approve(order.getQuantity());
    }

    @Override
    public RiskDecision evaluate(Signal signal) {
        RiskState currentState = riskService.getState();
        if (currentState.isHalted()) {
            return RiskDecision.reject(
                    RiskDecision.Reason.HALTED,
                    "System is HALTED",
                    Collections.singletonList("Halt check")
            );
        }

        BigDecimal quantity = calculateQuantity(signal, currentState);
        return RiskDecision.approve(quantity);
    }

    @Override
    public boolean isApprovalFresh(Order order) {
        RiskState currentState = riskService.getState();

        if (currentState.isHalted()) {
            log.error("[RiskGate] Stale approval detected: System is HALTED. Order: {}", order.getId());
            return false;
        }

        return true;
    }

    private BigDecimal calculateQuantity(Signal signal, RiskState state) {
        // Simple sizing logic: 1% of equity per trade
        BigDecimal riskPercent = new BigDecimal("0.01");

        BigDecimal baseCapital = state.getTotalEquity().max(state.getBalance());

        if (baseCapital.compareTo(BigDecimal.ZERO) <= 0 ||
                signal.getPrice() == null ||
                signal.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("0.001");
        }

        BigDecimal quantity = baseCapital.multiply(riskPercent)
                .divide(signal.getPrice(), 8, RoundingMode.HALF_UP);

        BigDecimal minQty = new BigDecimal("0.001");
        return quantity.compareTo(minQty) < 0 ? minQty : quantity;
    }
}
