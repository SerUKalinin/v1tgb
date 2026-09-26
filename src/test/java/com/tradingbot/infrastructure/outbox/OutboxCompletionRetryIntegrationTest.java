package com.tradingbot.infrastructure.outbox;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.service.execution.TradeService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.event.OrderExecutedEvent;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * F2 — Outbox completion retry / idempotency.
 *
 * Контракты:
 *
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 *
 * Проверяем:
 *
 * ORDER_EXECUTED
 *      ↓
 * downstream failure
 *      ↓
 * FAILED + retryCount=1
 *      ↓
 * retry
 *      ↓
 * Trade created exactly once
 * TRADE_CREATED persisted exactly once
 * ORDER_EXECUTED -> PROCESSED
 *
 * ВАЖНО:
 * Тест использует штатный BaseIntegrationTest datasource.
 * @AutoConfigureTestDatabase здесь НЕ используется, потому что
 * он заменяет datasource и ломает H2 schema для PostgreSQL JSONB.
 */
class OutboxCompletionRetryIntegrationTest
        extends BaseIntegrationTest {

    @Autowired
    private OutboxProcessor outboxProcessor;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private TradeRepository tradeRepository;

    @SpyBean
    private TradeService tradeService;

    @Test
    @DisplayName(
            "F2: failed ORDER_EXECUTED must retry and create Trade exactly once"
    )
    void shouldRetryOrderExecutedAndCreateTradeExactlyOnce() {

        // ============================================================
        // GIVEN — ORDER
        // ============================================================

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "f2-" + orderId,
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        new BigDecimal("0.001"),
                        new BigDecimal("100"),
                        "F2-TEST",
                        signalId
                );

        UUID executionId =
                order.getExecutionId();

        assertNotNull(
                executionId,
                "executionId must be created by Order.createPendingExecution()"
        );

        // ============================================================
        // GIVEN — EXECUTING -> FILLED
        // ============================================================

        ExecutionContext context =
                ExecutionContext.of(order);

        order.markExecuting(context);

        order.fill(
                context,
                "F2-EXCHANGE-ORDER-" + orderId,
                new BigDecimal("0.001"),
                new BigDecimal("100")
        );

        assertEquals(
                OrderStatus.FILLED,
                order.getStatus()
        );

        // ============================================================
        // GIVEN — PERSIST ORDER
        // ============================================================

        OrderEntity entity =
                orderMapper.toEntity(order);

        orderRepository.saveAndFlush(entity);

        // ============================================================
        // GIVEN — ORDER_EXECUTED EVENT
        // ============================================================

        String exchangeTradeId =
                "F2-TRADE-" + orderId;

        OrderExecutedEvent orderExecutedEvent =
                OrderExecutedEvent.from(
                        order,
                        exchangeTradeId
                );

        UUID orderExecutedEventId =
                IdentityFactory.deriveEventId(
                        executionId,
                        "ORDER_EXECUTED"
                );

        UUID tradeCreatedEventId =
                IdentityFactory.deriveEventId(
                        executionId,
                        "TRADE_CREATED"
                );

        // ============================================================
        // GIVEN — PERSIST ORDER_EXECUTED INTO OUTBOX
        // ============================================================

        outboxService.publishEvent(
                context,
                "ORDER",
                "ORDER_EXECUTED",
                orderExecutedEvent
        );

        assertEquals(
                true,
                outboxEventRepository
                        .findById(orderExecutedEventId)
                        .isPresent(),
                "ORDER_EXECUTED must be persisted in outbox"
        );

        // ============================================================
        // GIVEN — FAULT INJECTION
        // ============================================================

        /*
         * Первый вызов downstream должен упасть.
         *
         * Второй вызов должен выполнить реальный TradeService.
         */
        doThrow(
                new RuntimeException(
                        "SIMULATED_DOWNSTREAM_FAILURE"
                )
        )
                .doCallRealMethod()
                .when(tradeService)
                .onOrderExecuted(
                        any(OrderExecutedEvent.class),
                        any(ExecutionContext.class)
                );

        // ============================================================
        // WHEN — FIRST OUTBOX DELIVERY
        // ============================================================

        outboxProcessor.processOutbox();

        // ============================================================
        // THEN — FAILED
        // ============================================================

        var failedEvent =
                outboxEventRepository
                        .findById(orderExecutedEventId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "ORDER_EXECUTED event must exist after first attempt"
                                )
                        );

        assertEquals(
                OutboxStatus.FAILED,
                failedEvent.getStatus(),
                "First downstream failure must move event to FAILED"
        );

        assertEquals(
                1,
                failedEvent.getRetryCount(),
                "First failure must increment retryCount to 1"
        );

        assertEquals(
                0,
                tradeRepository.count(),
                "Trade must rollback together with failed downstream transaction"
        );

        assertEquals(
                0,
                outboxEventRepository
                        .findAll()
                        .stream()
                        .filter(event ->
                                "TRADE_CREATED".equals(
                                        event.getEventType()
                                )
                        )
                        .filter(event ->
                                executionId.equals(
                                        event.getExecutionId()
                                )
                        )
                        .count(),
                "TRADE_CREATED must not survive failed downstream transaction"
        );

        // ============================================================
        // GIVEN — RETRY WINDOW EXPIRED
        // ============================================================

        failedEvent.setNextAttemptAt(
                Instant.now().minusSeconds(1)
        );

        outboxEventRepository.saveAndFlush(
                failedEvent
        );

        // ============================================================
        // WHEN — SECOND OUTBOX DELIVERY
        // ============================================================

        outboxProcessor.processOutbox();

        // ============================================================
        // THEN — ORDER_EXECUTED PROCESSED
        // ============================================================

        var processedEvent =
                outboxEventRepository
                        .findById(orderExecutedEventId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "ORDER_EXECUTED event must still exist"
                                )
                        );

        assertEquals(
                OutboxStatus.PROCESSED,
                processedEvent.getStatus(),
                "Retry must process ORDER_EXECUTED successfully"
        );

        assertEquals(
                1,
                processedEvent.getRetryCount(),
                "Successful retry must preserve retryCount=1"
        );

        // ============================================================
        // THEN — EXACTLY ONE TRADE
        // ============================================================

        assertEquals(
                1,
                tradeRepository.count(),
                "Exactly one Trade must be created"
        );

        var trades =
                tradeRepository.findAll();

        assertEquals(
                1,
                trades.size()
        );

        assertEquals(
                exchangeTradeId,
                trades.get(0).getExchangeTradeId(),
                "Trade must preserve authoritative exchangeTradeId"
        );

        // ============================================================
        // THEN — EXACTLY ONE TRADE_CREATED OUTBOX EVENT
        // ============================================================

        long tradeCreatedCount =
                outboxEventRepository
                        .findAll()
                        .stream()
                        .filter(event ->
                                "TRADE_CREATED".equals(
                                        event.getEventType()
                                )
                        )
                        .filter(event ->
                                executionId.equals(
                                        event.getExecutionId()
                                )
                        )
                        .count();

        assertEquals(
                1,
                tradeCreatedCount,
                "Exactly one TRADE_CREATED event must be persisted"
        );

        assertEquals(
                true,
                outboxEventRepository
                        .findById(tradeCreatedEventId)
                        .isPresent(),
                "TRADE_CREATED must use deterministic event identity"
        );

        // ============================================================
        // THEN — DOWNSTREAM WAS INVOKED TWICE
        // ============================================================

        verify(
                tradeService,
                times(2)
        ).onOrderExecuted(
                any(OrderExecutedEvent.class),
                any(ExecutionContext.class)
        );
    }
}