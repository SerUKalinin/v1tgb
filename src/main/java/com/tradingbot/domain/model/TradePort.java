package com.tradingbot.domain.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradePort {

    boolean existsByExchangeTradeId(
            String exchangeTradeId
    );

    Optional<Trade> findByOrderId(
            UUID orderId
    );

    Trade save(
            Trade trade
    );

    List<Trade> findBySymbolAndStrategyId(
            String symbol,
            String strategyId
    );

    List<Trade> findAll();
}