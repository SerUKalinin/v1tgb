package com.tradingbot.application.service;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.OrderRequest;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderManagementService {

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final PositionService positionService;

    @Transactional
    public void registerOrder(OrderRequest request) {

        OrderEntity order = OrderEntity.builder()
                .id(UUID.randomUUID().toString())
                .clientOrderId(request.getClientOrderId())
                .symbol(request.getSymbol())
                .side(request.getSide())
                .quantity(request.getAmount())
                .price(request.getPrice())
                .createdAt(Instant.now())
                .build();

        orderRepository.save(order);

        log.info("[OMS] Order registered: {}", order.getId());
    }

    @Transactional
    public void processExecution(ExecutionResult result) {

        if (!result.isSuccess()) {
            log.warn("[OMS] Execution failed: symbol={}, error={}",
                    result.getSymbol(), result.getErrorMessage());
            return;
        }

        TradeEntity trade = TradeEntity.builder()
                .id(UUID.randomUUID().toString())
                .orderId(result.getOrderId())
                .symbol(result.getSymbol())
                .side(result.getSide())
                .quantity(result.getExecutedQty())
                .price(result.getExecutedPrice())
                .executedAt(result.getExecutedAt())
                .build();

        tradeRepository.save(trade);

        orderRepository.findById(result.getOrderId()).ifPresent(order -> {
            order.setPrice(result.getExecutedPrice());
            orderRepository.save(order);
        });

        positionService.applyExecution(result);

        log.info("[OMS] Execution processed: orderId={}", result.getOrderId());
    }
}