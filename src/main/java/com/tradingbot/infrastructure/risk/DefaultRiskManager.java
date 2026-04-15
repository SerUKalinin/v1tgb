package com.tradingbot.infrastructure.risk;

import com.tradingbot.domain.model.OrderRequest;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.risk.ExchangeFilterService;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Базовая реализация риск-менеджера, интегрированная с ExchangeFilterService.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultRiskManager implements RiskManager {

    private final ExchangeFilterService filterService;

    @Override
    public RiskDecision evaluate(Signal signal) {
        log.info("[RISK] Evaluating signal: {} {} @ {}", signal.getType(), signal.getSymbol(), signal.getPrice());
        // В будущем здесь можно добавить расчет размера позиции на основе волатильности
        return RiskDecision.approve(new BigDecimal("0.01"));
    }

    @Override
    public RiskDecision check(OrderEntity order) {
        log.info("[RISK] Checking order: {} {} {}", order.getSide(), order.getQuantity(), order.getSymbol());

        if (order.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return RiskDecision.reject("Quantity must be positive");
        }

        // Интеграция с новым движком правил через ExchangeFilterService
        OrderRequest request = OrderRequest.builder()
                .symbol(order.getSymbol())
                .quantity(order.getQuantity())
                .side(order.getSide())
                .type(order.getType())
                .price(order.getPrice())
                .build();

        return filterService.filter(request);
    }
}
