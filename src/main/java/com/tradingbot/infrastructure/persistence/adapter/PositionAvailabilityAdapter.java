package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.position.PositionAvailabilityPort;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Persistence-реализация domain-порта доступности позиции.
 */
@Component
@RequiredArgsConstructor
public class PositionAvailabilityAdapter implements PositionAvailabilityPort {

    private final PositionRepository positionRepository;

    @Override
    public BigDecimal getAvailableQuantity(
            String symbol,
            String strategyId
    ) {
        return positionRepository
                .findBySymbolAndStrategyId(symbol, strategyId)
                .map(position -> position.getQuantity())
                .orElse(BigDecimal.ZERO);
    }
}