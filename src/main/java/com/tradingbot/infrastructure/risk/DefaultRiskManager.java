package com.tradingbot.infrastructure.risk;

import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Базовая реализация риск-менеджера.
 */
@Component
@Slf4j
public class DefaultRiskManager implements RiskManager {

    @Override
    public RiskDecision evaluate(Signal signal) {
        log.info("[RISK] Evaluating signal: {} {} @ {}", signal.getType(), signal.getSymbol(), signal.getPrice());
        // Базовая логика для сигналов
        return RiskDecision.approve(new BigDecimal("0.01"));
    }

    @Override
    public RiskDecision check(OrderEntity order) {
        log.info("[RISK] Checking order: {} {} {}", order.getSide(), order.getQuantity(), order.getSymbol());

        if (order.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return RiskDecision.reject("Quantity must be positive");
        }

        // Пример ограничения: не более 0.1 BTC за раз
        BigDecimal maxAmount = new BigDecimal("0.1");
        if (order.getSymbol().contains("BTC") && order.getQuantity().compareTo(maxAmount) > 0) {
            log.warn("[RISK] Reducing size from {} to {}", order.getQuantity(), maxAmount);
            return RiskDecision.reduce(maxAmount, "Max BTC amount exceeded");
        }

        return RiskDecision.approve(order.getQuantity());
    }}