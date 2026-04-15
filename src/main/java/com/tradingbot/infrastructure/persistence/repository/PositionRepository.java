package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * Репозиторий для работы с сущностью PositionEntity в базе данных.
 */
@Repository
public interface PositionRepository extends JpaRepository<PositionEntity, String> {
    Optional<PositionEntity> findBySymbolAndStrategyId(String symbol, String strategyId);
}