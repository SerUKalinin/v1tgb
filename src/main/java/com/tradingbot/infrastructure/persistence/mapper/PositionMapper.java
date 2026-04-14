package com.tradingbot.infrastructure.persistence.mapper;

import com.tradingbot.domain.model.Position;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import org.springframework.stereotype.Component;

@Component
public class PositionMapper {

    public Position toDomain(PositionEntity entity) {
        return new Position(
                entity.getSymbol(),
                entity.getQuantity(),
                entity.getEntryPrice()
        );
    }

    public PositionEntity toEntity(Position position) {
        PositionEntity entity = new PositionEntity();
        entity.setSymbol(position.getSymbol());
        entity.setQuantity(position.getQuantity());
        entity.setEntryPrice(position.getEntryPrice());
        return entity;
    }
}