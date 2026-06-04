package com.tradingbot.application.service.risk;

import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.policy.TransitionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceIsolationTest {

    @Mock private OrderRepositoryPort orderRepository;
    @Mock private ExchangeOrderQueryService exchangeQueryService;

    @InjectMocks
    private ReconciliationService reconciliationService;

    private UUID orderId;
    private Order order;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();

        order = Order.builder()
                .id(orderId)
                .clientOrderId("client-123")
                .status(OrderStatus.EXECUTING)
                .build();
    }

    @Test
    void shouldSkipReconciliationWhenOrderIsExecutingAndNotStale() {
        when(orderRepository.claimForReconciliation(orderId))
                .thenReturn(Optional.empty());

        reconciliationService.syncOrderWithExchange(order);

        verify(exchangeQueryService, never()).getOrderStatus(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void shouldAllowReconciliationWhenOrderIsUnknown() {
        Order unknownOrder = Order.builder()
                .id(orderId)
                .clientOrderId("client-123")
                .status(OrderStatus.UNKNOWN)
                .originalQuantity(BigDecimal.ONE)
                .price(BigDecimal.TEN)
                .build();

        when(orderRepository.claimForReconciliation(orderId))
                .thenReturn(Optional.of(unknownOrder));

        when(exchangeQueryService.getOrderStatus("client-123"))
                .thenReturn(ExecutionResult.builder()
                        .status(ExecutionResult.Status.SUCCESS)
                        .exchangeOrderId("ex-123")
                        .executedQty(BigDecimal.ONE)
                        .executedPrice(BigDecimal.TEN)
                        .build());

        reconciliationService.syncOrderWithExchange(unknownOrder);

        verify(orderRepository).save(argThat(o ->
                o.getStatus() == OrderStatus.FILLED
        ));
    }

    @Test
    void shouldSkipReconciliationWhenOrderIsTerminal() {
        Order filled = Order.builder()
                .id(orderId)
                .clientOrderId("client-123")
                .status(OrderStatus.FILLED)
                .build();

        reconciliationService.syncOrderWithExchange(filled);

        verify(orderRepository, never()).save(any());
    }
}