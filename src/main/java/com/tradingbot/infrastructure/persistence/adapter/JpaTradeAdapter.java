package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.model.Trade;
import com.tradingbot.domain.model.TradePort;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import com.tradingbot.infrastructure.persistence.mapper.TradeMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JpaTradeAdapter
        implements TradePort {

    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final TradeMapper tradeMapper;

    @Override
    @Transactional(readOnly = true)
    public boolean existsByExchangeTradeId(
            String exchangeTradeId
    ) {
        return tradeRepository.existsByExchangeTradeId(
                exchangeTradeId
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Trade> findByOrderId(
            UUID orderId
    ) {

        return tradeRepository
                .findFirstByOrder_Id(
                        orderId
                )
                .map(
                        tradeMapper::toDomain
                );
    }

    @Override
    @Transactional
    public Trade save(
            Trade trade
    ) {

        if (trade == null) {
            throw new IllegalArgumentException(
                    "trade cannot be null"
            );
        }

        if (trade.getOrderId() == null) {
            throw new IllegalArgumentException(
                    "trade.orderId cannot be null"
            );
        }

        OrderEntity order =
                orderRepository
                        .findById(
                                trade.getOrderId()
                        )
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "Order not found: "
                                                + trade.getOrderId()
                                )
                        );

        TradeEntity entity =
                tradeMapper.toEntity(
                        trade
                );

        if (entity == null) {
            throw new IllegalStateException(
                    "TradeMapper returned null entity"
            );
        }

        /*
         * Replace mapper's lightweight order
         * with managed persistence entity.
         */
        entity.setOrder(
                order
        );

        TradeEntity saved =
                tradeRepository.save(
                        entity
                );

        return tradeMapper.toDomain(
                saved
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<Trade>
    findBySymbolAndStrategyId(
            String symbol,
            String strategyId
    ) {

        return tradeRepository
                .findBySymbolAndStrategyIdOrderByExecutedAtAsc(
                        symbol,
                        strategyId
                )
                .stream()
                .map(
                        tradeMapper::toDomain
                )
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Trade> findAll() {

        return tradeRepository
                .findAllByOrderByExecutedAtAsc()
                .stream()
                .map(
                        tradeMapper::toDomain
                )
                .toList();
    }
}