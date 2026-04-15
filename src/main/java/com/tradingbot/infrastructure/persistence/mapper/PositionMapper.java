package com.tradingbot.infrastructure.persistence.mapper;

import com.tradingbot.domain.model.Position;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import org.springframework.stereotype.Component;

/**
 * Маппер для преобразования между Position (доменная модель) и PositionEntity (JPA-сущность).
 */
@Component
public class PositionMapper {

    /**
     * Преобразует JPA-сущность в доменную модель.
     *
     * @param entity JPA-сущность позиции
     * @return доменная модель позиции
     */
    public Position toDomain(PositionEntity entity) {
        return Position.builder()
                .symbol(entity.getSymbol())
                .strategyId(entity.getStrategyId())
                .netQuantity(entity.getQuantity())
                .avgEntryPrice(entity.getEntryPrice())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * Преобразует доменную модель в JPA-сущность.
     *
     * @param position доменная модель позиции
     * @return JPA-сущность позиции
     */
    public PositionEntity toEntity(Position position) {
        return PositionEntity.builder()
                .symbol(position.getSymbol())
                .quantity(position.getNetQuantity())
                .entryPrice(position.getAvgEntryPrice())
                .build();
    }}