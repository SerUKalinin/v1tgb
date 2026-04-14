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
        return new Position(
                entity.getSymbol(),
                entity.getQuantity(),
                entity.getEntryPrice()
        );
    }

    /**
     * Преобразует доменную модель в JPA-сущность.
     *
     * @param position доменная модель позиции
     * @return JPA-сущность позиции
     */
    public PositionEntity toEntity(Position position) {
        PositionEntity entity = new PositionEntity();
        entity.setSymbol(position.getSymbol());
        entity.setQuantity(position.getQuantity());
        entity.setEntryPrice(position.getEntryPrice());
        return entity;
    }
}