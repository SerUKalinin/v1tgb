package com.tradingbot.domain.model;

import java.util.List;

public interface TradePort {

    boolean existsByExchangeTradeId(String exchangeTradeId);

    Trade save(Trade trade);

    List<Trade> findBySymbolAndStrategyId(
            String symbol,
            String strategyId
    );

    List<Trade> findAll();
}