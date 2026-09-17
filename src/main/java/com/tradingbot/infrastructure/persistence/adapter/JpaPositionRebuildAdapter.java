package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.model.PositionRebuildPort;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class JpaPositionRebuildAdapter implements PositionRebuildPort {

    private final PositionRepository positionRepository;

    @Override
    @Transactional
    public void clear() {
        positionRepository.deleteAllInBatch();
    }

    @Override
    @Transactional
    public void save(Position position, BigDecimal realizedPnl) {
        if (position == null) {
            throw new IllegalArgumentException("position cannot be null");
        }

        PositionEntity entity = PositionEntity.builder()
                .symbol(position.getSymbol())
                .strategyId(position.getStrategyId())
                .quantity(position.getNetQuantity())
                .entryPrice(position.getAvgEntryPrice())
                .realizedPnl(realizedPnl)
                .updatedAt(position.getUpdatedAt())
                .version(0L)
                .build();

        positionRepository.save(entity);
    }
}