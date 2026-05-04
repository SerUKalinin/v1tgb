package com.tradingbot.infrastructure.persistence.mapper;

import com.tradingbot.domain.model.Trade;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TradeMapper {

    public TradeEntity toEntity(Trade domain) {
        if (domain == null) return null;

        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setId(domain.getOrderId());

        return TradeEntity.builder()
                .id(domain.getId())
                .order(orderEntity)
                .clientOrderId(domain.getClientOrderId())           // ✅ Теперь поле есть
                .exchangeTradeId(domain.getExchangeTradeId())
                .symbol(domain.getSymbol())
                .strategyId(domain.getStrategyId())
                .side(domain.getSide())
                .quantity(domain.getQuantity())
                .price(domain.getPrice())
                .commission(domain.getFeeAmount())                  // ✅ feeAmount -> commission
                .commissionAsset(domain.getFeeAsset())
                .executedAt(domain.getExecutedAt())
                .build();
    }
    public Trade toDomain(TradeEntity entity) {
        if (entity == null) return null;

        return Trade.builder()
                .id(entity.getId())
                .orderId(entity.getOrder() != null ? entity.getOrder().getId() : null)
                .clientOrderId(entity.getClientOrderId())           // ✅ Теперь метод есть
                .exchangeTradeId(entity.getExchangeTradeId())
                .symbol(entity.getSymbol())
                .strategyId(entity.getStrategyId())
                .side(entity.getSide())
                .quantity(entity.getQuantity())
                .price(entity.getPrice())
                .feeAmount(entity.getCommission())                  // ✅ commission -> feeAmount
                .feeAsset(entity.getCommissionAsset())
                .executedAt(entity.getExecutedAt())
                .build();
    }}