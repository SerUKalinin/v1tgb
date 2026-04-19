package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.domain.model.PositionEntity;
import com.tradingbot.domain.model.PositionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

// ИЗМЕНЕНО: JpaRepository<PositionEntity, String> → JpaRepository<PositionEntity, Long>
@Repository
public interface PositionRepository extends JpaRepository<PositionEntity, Long> {

    // Бизнес-ключ остался — но теперь это уникальный индекс, не PK
    Optional<PositionEntity> findBySymbolAndStrategyIdAndUserId(
            String symbol, String strategyId, Long userId);

    // Совместимость: без userId (для однопользовательских сценариев / migration period)
    Optional<PositionEntity> findBySymbolAndStrategyId(String symbol, String strategyId);

    // Только открытые позиции
    Optional<PositionEntity> findBySymbolAndStrategyIdAndUserIdAndStatus(
            String symbol, String strategyId, Long userId, PositionStatus status);
}
