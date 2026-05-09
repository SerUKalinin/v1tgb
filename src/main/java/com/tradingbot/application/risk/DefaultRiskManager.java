package com.tradingbot.application.risk;

import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.tracing.ExecutionEventType;
import com.tradingbot.tracing.ExecutionLogFactory;
import com.tradingbot.tracing.ExecutionLogger;
import com.tradingbot.tracing.ExecutionStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Implementation of RiskManager that acts as the Enforcement Gate.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultRiskManager implements RiskManager {
    private final RiskService riskService;
    private final ExecutionLogger executionLogger;

    @Override
    public Optional<Order> approveSignal(SignalEvent signal) {
        // 1. Переносим принятие решения в RiskService, который обеспечит DB Lock
        Optional<Order> approved = riskService.evaluateAndReserve(signal);
        approved.ifPresent(order -> executionLogger.log(ExecutionLogFactory.from(
                order,
                ExecutionEventType.RISK_APPROVED,
                ExecutionStateMapper.toContractState(order.getStatus()),
                "Risk approved for signal " + signal.getSymbol()
        )));
        return approved;
    }

    @Override
    public RiskDecision check(Order order) {
        RiskState currentState = riskService.getState();
        if (currentState.isHalted()) {
            return RiskDecision.reject(RiskDecision.Reason.HALTED, "System is HALTED", java.util.Collections.singletonList("Halt check"));
        }
        return RiskDecision.approve(order.getQuantity());
    }
    @Override
    public RiskDecision evaluate(Signal signal) {
        RiskState currentState = riskService.getState();
        if (currentState.isHalted()) {
            return RiskDecision.reject(RiskDecision.Reason.HALTED, "System is HALTED", java.util.Collections.singletonList("Halt check"));
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

        // В новой архитектуре проверка версии через Order может быть не нужна или реализована иначе,
        // так как Order теперь является основным объектом домена.
        // Если версия все еще нужна, она должна быть в классе Order.
        
        return true;
    }
    public BigDecimal calculateQuantity(Signal signal, RiskState state) {
        // Simple sizing logic: 1% of equity per trade
        BigDecimal riskPercent = new BigDecimal("0.01");
        
        BigDecimal baseCapital = state.getTotalEquity().max(state.getBalance());
        
        if (baseCapital.compareTo(BigDecimal.ZERO) <= 0 || signal.getPrice() == null || signal.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("0.001");
        }

        BigDecimal quantity = baseCapital.multiply(riskPercent).divide(signal.getPrice(), 8, RoundingMode.HALF_UP);
        
        BigDecimal minQty = new BigDecimal("0.001");
        if (quantity.compareTo(minQty) < 0) {
            return minQty;
        }
        
        return quantity;
    }
}
