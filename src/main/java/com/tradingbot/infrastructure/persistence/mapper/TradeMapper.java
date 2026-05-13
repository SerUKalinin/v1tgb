package com.tradingbot.infrastructure.persistence.mapper;

import com.tradingbot.domain.model.Trade;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import org.springframework.stereotype.Component;

/**
 * Маппер для преобразования между JPA сущностью TradeEntity и доменной моделью Trade.
 * Использует Builder-pattern для обеспечения неизменяемости identity-полей.
 */
@Component
public class TradeMapper {

    public TradeEntity toEntity(Trade domain) {
        if (domain == null) return null;

        OrderEntity orderReference = OrderEntity.builder()
                .id(domain.getOrderId())
                .build();

        return TradeEntity.builder()
                .id(domain.getId())
                .order(orderReference)
                .clientOrderId(domain.getClientOrderId())
                .exchangeTradeId(domain.getExchangeTradeId())
                .symbol(domain.getSymbol())
                .strategyId(domain.getStrategyId())
                .side(domain.getSide())
                .quantity(domain.getQuantity())
                .price(domain.getPrice())
                .commission(domain.getFeeAmount())
                .commissionAsset(domain.getFeeAsset())
                .realizedPnl(domain.getRealizedPnl())
                .executedAt(domain.getExecutedAt())
                .build();
    }

    public Trade toDomain(TradeEntity entity) {
        if (entity == null) return null;

        return Trade.builder()
                .id(entity.getId())
                .orderId(entity.getOrder() != null ? entity.getOrder().getId() : null)
                .clientOrderId(entity.getClientOrderId())
                .exchangeTradeId(entity.getExchangeTradeId())
                .symbol(entity.getSymbol())
                .strategyId(entity.getStrategyId())
                .side(entity.getSide())
                .quantity(entity.getQuantity())
                .price(entity.getPrice())
                .feeAmount(entity.getCommission())
                .feeAsset(entity.getCommissionAsset())
                .realizedPnl(entity.getRealizedPnl())
                .executedAt(entity.getExecutedAt())
                .build();
    }
}
