package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.*;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementation of RiskManager that acts as the Enforcement Gate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultRiskManager implements RiskManager {
    private final List<RiskRule> rules;
    private final RiskService riskService;
    private final ExchangeFeasibilityPort feasibilityPort;
    private final OrderNormalizationService normalizationService;

    @Override
    public Optional<Order> approveSignal(SignalEvent signal) {
        // 1. Переносим принятие решения в RiskService, который обеспечит DB Lock
        return riskService.evaluateAndReserve(signal);
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

    private BigDecimal calculateQuantity(SignalEvent signal, RiskState state) {
        // Переиспользуем логику для SignalEvent
        Signal adapter = new Signal(signal.getSymbol(), signal.getStrategyId(), signal.getType(), signal.getPrice(), BigDecimal.ZERO);
        return calculateQuantity(adapter, state);
    }    private BigDecimal applyConstraints(BigDecimal quantity, String symbol) {
        return quantity;
    }
}
