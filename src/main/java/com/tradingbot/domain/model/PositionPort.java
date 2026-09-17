package com.tradingbot.domain.model;

import com.tradingbot.domain.event.TradeCreatedEvent;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary для Position.
 *
 * <p>Domain/application работают только с Position,
 * не зная о JPA Entity, Repository или Mapper.
 */
public interface PositionPort {

    List<Position> findAll();

    Optional<Position> findBySymbolAndStrategyId(
            String symbol,
            String strategyId
    );

    Position applyTrade(
            TradeCreatedEvent event
    );
}