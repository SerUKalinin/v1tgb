package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.bootstrap.TradingSystemBootstrapper;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.application.service.risk.ReconciliationService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.infrastructure.outbox.OutboxEventMapper;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.ExecutionClaimRepository;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class OrderExecutionIdempotencyIntegrationTest
        extends BaseIntegrationTest {

    @Autowired
    private OrderExecutionHandler executionHandler;

    @Autowired
    private ExecutionClaimRepository claimRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ReconciliationService reconciliationService;

    @MockBean
    private TradingSystemBootstrapper tradingSystemBootstrapper;

    @MockBean
    private SystemStateManager systemStateManager;

    @MockBean
    private ExecutionPort executionPort;

    @BeforeEach
    void setUp() {

        when(
                systemStateManager.isReady()
        ).thenReturn(true);

        when(
                executionPort.placeOrder(any())
        ).thenAnswer(
                invocation ->
                        ExecutionResult.exchangeStateUnknown(
                                invocation
                                        .getArgument(
                                                0,
                                                com.tradingbot.domain.model.Order.class
                                        )
                                        .getId()
                        )
        );
    }

    @Test
    void testDuplicateOutboxEventDoesNotCreateNewClaim()
            throws Exception {

        UUID signalId =
                UUID.randomUUID();

        UUID orderId =
                UUID.randomUUID();

        UUID executionId =
                UUID.randomUUID();

        String clientOrderId =
                "CL-" + orderId;

        OrderEntity orderEntity =
                OrderEntity.builder()
                        .id(orderId)
                        .clientOrderId(clientOrderId)
                        .symbol("BTCUSDT")
                        .side(OrderSide.BUY)
                        .type(OrderType.MARKET)
                        .strategyId("test-strat")
                        .signalId(signalId)
                        .executionId(executionId)
                        .quantity(BigDecimal.ONE)
                        .price(
                                BigDecimal.valueOf(50000)
                        )
                        .status(
                                OrderStatus.PENDING_EXECUTION
                        )
                        .version(0L)
                        .executionAttempts(0)
                        .build();

        orderRepository.saveAndFlush(
                orderEntity
        );

        OrderCreatedEvent payload =
                new OrderCreatedEvent(
                        signalId,
                        orderId,
                        executionId
                );

        OutboxEventEntity event =
                OutboxEventEntity.builder()
                        .id(UUID.randomUUID())
                        .eventId(UUID.randomUUID())
                        .aggregateId(orderId)
                        .signalId(signalId)
                        .orderId(orderId)
                        .executionId(executionId)
                        .causationId(orderId)
                        .aggregateType("ORDER")
                        .eventType("ORDER_CREATED")
                        .payload(
                                objectMapper.writeValueAsString(
                                        payload
                                )
                        )
                        .status(OutboxStatus.NEW)
                        .sequenceNumber(1L)
                        .retryCount(0)
                        .attemptCount(1)
                        .schemaVersion(1)
                        .createdAt(Instant.now())
                        .build();

        outboxRepository.saveAndFlush(
                event
        );

        /*
         * FIRST PROCESSING
         */
        executionHandler.consume(
                OutboxEventMapper.toDomain(event)
        );

        long countAfterFirst =
                claimRepository.count();

        assertTrue(
                countAfterFirst > 0,
                "First consume should create an execution claim"
        );

        assertTrue(
                claimRepository.existsByExecutionId(
                        executionId
                ),
                "First consume must create claim for the expected executionId"
        );

        /*
         * SECOND PROCESSING OF THE SAME EVENT
         */
        executionHandler.consume(
                OutboxEventMapper.toDomain(event)
        );

        assertEquals(
                countAfterFirst,
                claimRepository.count(),
                "Second processing of the same ORDER_CREATED must not create additional claims"
        );

        assertTrue(
                claimRepository.existsByExecutionId(
                        executionId
                ),
                "Original execution claim must remain present"
        );
    }
}