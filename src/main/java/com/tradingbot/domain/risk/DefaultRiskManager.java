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
    private final RiskStateStore stateStore;
    private final ExchangeFeasibilityPort feasibilityPort;
    private final OrderNormalizationService normalizationService;
    private final Map<String, Lock> symbolLocks = new ConcurrentHashMap<>();

    @Override
    public Optional<ApprovedOrder> approveSignal(SignalEvent signal) {
        Lock lock = symbolLocks.computeIfAbsent(signal.getSymbol(), k -> new ReentrantLock());
        
        if (!lock.tryLock()) {
            log.warn("[Risk] Concurrent signal processing for symbol: {}", signal.getSymbol());
            return Optional.empty();
        }

        try {
            RiskState currentState = stateStore.getState();
            
            // 1. Check if Halted
            if (currentState.isHalted()) {
                log.error("[Risk] System is HALTED. Rejecting signal for {}", signal.getSymbol());
                return Optional.empty();
            }

            // 2. Calculate Quantity (Centralized Sizing)
            BigDecimal rawQuantity = calculateQuantity(signal, currentState);
            
            // 3. Normalization
            NormalizedOrder normalized = normalizationService.normalize(new FeasibilityRequest(
                    signal.getSymbol(),
                    rawQuantity,
                    signal.getPrice()
            ));

            // 4. Feasibility Check
            FeasibilityResult feasibility = feasibilityPort.check(new FeasibilityRequest(
                    normalized.getSymbol(),
                    normalized.getQuantity(),
                    normalized.getPrice()
            ));

            if (!feasibility.isFeasible()) {
                log.warn("[Risk-EFL] Signal rejected by exchange constraints: {} - Reason: {}", 
                        signal.getSymbol(), feasibility.getReason());
                return Optional.empty();
            }

            // 5. Create ApprovedOrder
            UUID orderId = UUID.randomUUID();
            ApprovedOrder approvedOrder = new ApprovedOrder(
                    orderId,
                    com.tradingbot.common.util.ClientOrderIdGenerator.generate(orderId),
                    signal.getSymbol(),
                    signal.getType() == com.tradingbot.common.enums.SignalType.BUY ? OrderSide.BUY : OrderSide.SELL,
                    OrderType.MARKET,
                    normalized.getQuantity(),
                    normalized.getPrice(),
                    signal.getStopLoss(),
                    signal.getTakeProfit(),
                    signal.getStrategyId(),
                    Instant.now(),
                    currentState.getVersion()
            );

            log.info("[Risk] Signal APPROVED: {} {} qty={} (raw={})", 
                    approvedOrder.getSymbol(), approvedOrder.getSide(), normalized.getQuantity(), rawQuantity);
            return Optional.of(approvedOrder);

        } finally {
            lock.unlock();
        }
    }

    @Override
    public RiskDecision check(Order order) {
        RiskState currentState = stateStore.getState();
        if (currentState.isHalted()) {
            return RiskDecision.reject("System is HALTED");
        }
        return RiskDecision.approve(order.getQuantity());
    }
    @Override
    public RiskDecision evaluate(Signal signal) {
        RiskState currentState = stateStore.getState();
        if (currentState.isHalted()) {
            return RiskDecision.reject("System is HALTED");
        }

        // Используем существующую логику расчета объема
        BigDecimal quantity = calculateQuantity(signal, currentState);
        
        // В будущем здесь можно добавить проверку правил (rules)
        for (RiskRule rule : rules) {
            // Если правила поддерживают Signal, можно добавить проверку
        }

        return RiskDecision.approve(quantity);
    }

    @Override
    public boolean isApprovalFresh(ApprovedOrder approvedOrder) {
        RiskState currentState = stateStore.getState();
        
        if (currentState.isHalted()) {
            log.error("[RiskGate] Stale approval detected: System is HALTED. Order: {}", approvedOrder.getOrderId());
            return false;
        }

        if (approvedOrder.getRiskStateVersion() != currentState.getVersion()) {
            log.warn("[RiskGate] Stale approval detected: Version mismatch. Order version: {}, Current version: {}", 
                    approvedOrder.getRiskStateVersion(), currentState.getVersion());
        }

        return true;
    }

    private BigDecimal calculateQuantity(Signal signal, RiskState state) {
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
