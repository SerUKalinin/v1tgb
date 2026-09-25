package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TradeRepository
        extends JpaRepository<
        TradeEntity,
        UUID
        > {

    List<TradeEntity> findAllByExecutedAtAfter(
            Instant executedAt
    );

    boolean existsByExchangeTradeId(
            String exchangeTradeId
    );

    Optional<TradeEntity> findFirstByOrder_Id(
            UUID orderId
    );

    List<TradeEntity>
    findBySymbolAndStrategyIdOrderByExecutedAtAsc(
            String symbol,
            String strategyId
    );

    List<TradeEntity>
    findAllByOrderByExecutedAtAsc();
}