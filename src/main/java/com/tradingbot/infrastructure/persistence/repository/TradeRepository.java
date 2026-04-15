package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TradeRepository extends JpaRepository<TradeEntity, Long> {

    
    Optional<TradeEntity> findByExchangeTradeId(String exchangeTradeId);

    boolean existsByExchangeTradeId(String exchangeTradeId);

    boolean existsByClientOrderId(String clientOrderId);

    long countByExchangeTradeId(String exchangeTradeId);

    List<TradeEntity> findBySymbolAndStrategyIdOrderByExecutedAtAsc(String symbol, String strategyId);    
    List<TradeEntity> findAllByOrderByExecutedAtAsc();
}