package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.model.PositionPort;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.mapper.PositionMapper;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import com.tradingbot.tracing.IdentityFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of PositionPort.
 *
 * <p>Все persistence details остаются внутри infrastructure.
 */
@Component
@RequiredArgsConstructor
public class JpaPositionAdapter implements PositionPort {

    private final PositionRepository repository;
    private final PositionMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<Position> findAll() {
        return repository.findAll()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Position> findBySymbolAndStrategyId(
            String symbol,
            String strategyId
    ) {
        return repository
                .findBySymbolAndStrategyId(symbol, strategyId)
                .map(mapper::toDomain);
    }

    @Override
    @Transactional
    public Position applyTrade(
            TradeCreatedEvent event
    ) {
        PositionEntity entity =
                repository
                        .findBySymbolAndStrategyId(
                                event.getSymbol(),
                                event.getStrategyId()
                        )
                        .orElseGet(
                                () -> createNewPositionEntity(event)
                        );

        BigDecimal signedQuantity =
                switch (event.getSide()) {
                    case BUY -> event.getQuantity();
                    case SELL -> event.getQuantity().negate();
                };

        entity.applyTrade(
                signedQuantity,
                event.getPrice(),
                event.getTradeId()
        );

        entity.updateStopLoss(
                event.getStopLoss()
        );

        entity.updateTakeProfit(
                event.getTakeProfit()
        );

        PositionEntity saved =
                repository.save(entity);

        return mapper.toDomain(saved);
    }

    private PositionEntity createNewPositionEntity(
            TradeCreatedEvent event
    ) {
        return PositionEntity.builder()
                .id(
                        IdentityFactory.derive(
                                UUID.fromString(
                                        event.getBusiness().orderId()
                                ),
                                "position"
                        )
                )
                .symbol(event.getSymbol())
                .strategyId(event.getStrategyId())
                .quantity(BigDecimal.ZERO)
                .entryPrice(BigDecimal.ZERO)
                .realizedPnl(BigDecimal.ZERO)
                .version(0L)
                .build();
    }
}