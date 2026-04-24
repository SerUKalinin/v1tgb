package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PositionRepository extends JpaRepository<PositionEntity, java.util.UUID> {
    Optional<PositionEntity> findBySymbol(String symbol);

    Optional<PositionEntity> findBySymbolAndStrategyId(String symbol, String strategyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PositionEntity p WHERE p.symbol = :symbol AND p.strategyId = :strategyId")
    Optional<PositionEntity> findBySymbolAndStrategyIdForUpdate(@Param("symbol") String symbol, @Param("strategyId") String strategyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PositionEntity p WHERE p.symbol = :symbol")
    Optional<PositionEntity> findBySymbolForUpdate(@Param("symbol") String symbol);
}