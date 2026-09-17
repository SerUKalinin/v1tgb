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
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderExecutionRaceConditionTest
        extends BaseIntegrationTest {

    @Autowired
    private OrderExecutionHandler executionHandler;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SystemStateManager systemStateManager;

    @MockBean
    private ExecutionPort executionPort;

    @BeforeEach
    void setUpSystemState() {

        when(
                systemStateManager.isReady()
        ).thenReturn(true);
    }

    @Test
    void testParallelExecutionDoesNotDoubleFill()
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
                        .signalId(signalId)
                        .executionId(executionId)
                        .symbol("BTCUSDT")
                        .side(OrderSide.BUY)
                        .type(OrderType.MARKET)
                        .strategyId("test-strat")
                        .status(
                                OrderStatus.PENDING_EXECUTION
                        )
                        .quantity(BigDecimal.ONE)
                        .price(
                                BigDecimal.valueOf(50000)
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

        when(
                executionPort.placeOrder(any())
        ).thenAnswer(
                invocation ->
                        ExecutionResult.exchangeStateUnknown(
                                orderId
                        )
        );

        int threadCount = 3;

        ExecutorService executor =
                Executors.newFixedThreadPool(
                        threadCount
                );

        List<Throwable> failures =
                new CopyOnWriteArrayList<>();

        OutboxEventMapper.toDomain(event);

        try {

            List<CompletableFuture<Void>> futures =
                    new ArrayList<>(threadCount);

            for (int i = 0; i < threadCount; i++) {

                futures.add(
                        CompletableFuture.runAsync(
                                () -> {

                                    try {

                                        executionHandler.consume(
                                                OutboxEventMapper.toDomain(
                                                        event
                                                )
                                        );

                                    } catch (Throwable e) {

                                        failures.add(e);
                                    }

                                },
                                executor
                        )
                );
            }

            CompletableFuture.allOf(
                    futures.toArray(
                            new CompletableFuture[0]
                    )
            ).join();

        } finally {

            executor.shutdown();
        }

        if (!failures.isEmpty()) {

            StringBuilder message =
                    new StringBuilder(
                            "Конкурентный consume() завершился исключением."
                    );

            for (int i = 0;
                 i < failures.size();
                 i++) {

                Throwable failure =
                        failures.get(i);

                message
                        .append("\n\n--- FAILURE ")
                        .append(i + 1)
                        .append(" ---\n")
                        .append(failure);

                Throwable cause =
                        failure.getCause();

                if (cause != null) {

                    message
                            .append("\nCAUSE: ")
                            .append(cause);
                }
            }

            fail(
                    message.toString()
            );
        }

        verify(
                executionPort,
                times(1)
        ).placeOrder(
                any()
        );

        OrderEntity finalOrder =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        assertEquals(
                1,
                finalOrder.getExecutionAttempts(),
                "Один ORDER_CREATED не должен приводить " +
                        "более чем к одной попытке исполнения"
        );

        assertEquals(
                executionId,
                finalOrder.getExecutionId(),
                "executionId ордера не должен измениться " +
                        "во время конкурентного исполнения"
        );
    }
}