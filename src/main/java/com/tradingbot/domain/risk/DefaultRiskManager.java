package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
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
            BigDecimal quantity = calculateQuantity(signal, currentState);
            
            // 3. Apply Constraints (LOT_SIZE, MIN_NOTIONAL - placeholder for now)
            quantity = applyConstraints(quantity, signal.getSymbol());

            // 4. Evaluate Rules (Optional for Stage 3, can be expanded)

            // 5. Create ApprovedOrder
            ApprovedOrder approvedOrder = new ApprovedOrder(
                    UUID.randomUUID().toString(),
                    "c-" + UUID.randomUUID().toString().substring(0, 8),
                    signal.getSymbol(),
                    signal.getType() == com.tradingbot.common.enums.SignalType.BUY ? OrderSide.BUY : OrderSide.SELL,
                    OrderType.MARKET,
                    quantity,
                    signal.getPrice(),
                    signal.getStrategyId(),
                    Instant.now(),
                    currentState.getVersion()
            );

            log.info("[Risk] Signal APPROVED: {} {} qty={}", approvedOrder.getSymbol(), approvedOrder.getSide(), approvedOrder.getQuantity());
            return Optional.of(approvedOrder);

        } finally {
            lock.unlock();
        }
    }

    @Override
    public RiskDecision check(OrderEntity order) {
        RiskState currentState = stateStore.getState();
        if (currentState.isHalted()) {
            return RiskDecision.reject("System is HALTED");
        }
        return RiskDecision.approve(order.getQuantity());
    }

    private BigDecimal calculateQuantity(SignalEvent signal, RiskState state) {
        // Simple sizing logic: 1% of equity per trade
        BigDecimal riskPercent = new BigDecimal("0.01");
        
        // Используем максимум из Equity и Balance для обеспечения ликвидности расчетов
        BigDecimal baseCapital = state.getTotalEquity().max(state.getBalance());
        
        if (baseCapital.compareTo(BigDecimal.ZERO) <= 0 || signal.getPrice() == null || signal.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[Risk] Base capital or price is invalid. Using default minimum quantity.");
            return new BigDecimal("0.001");
        }

        BigDecimal quantity = baseCapital.multiply(riskPercent).divide(signal.getPrice(), 8, RoundingMode.HALF_UP);
        
        // Гарантируем минимальный объем (защита от 0E-8)
        BigDecimal minQty = new BigDecimal("0.001");
        if (quantity.compareTo(minQty) < 0) {
            log.debug("[Risk] Calculated quantity {} is too low, rounding up to {}", quantity, minQty);
            return minQty;
        }
        
        return quantity;
    }

    private BigDecimal applyConstraints(BigDecimal quantity, String symbol) {
        return quantity;
    }
}
