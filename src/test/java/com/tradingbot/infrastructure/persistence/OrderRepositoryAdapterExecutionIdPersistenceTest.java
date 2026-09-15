package com.tradingbot.infrastructure.persistence;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.persistence.adapter.OrderRepositoryAdapter;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.tracing.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest
@ActiveProfiles("test")
@Import({
        OrderRepositoryAdapter.class,
        OrderMapper.class
})
class OrderRepositoryAdapterExecutionIdPersistenceTest {

    @Autowired
    private OrderRepositoryAdapter orderRepositoryAdapter;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderMapper orderMapper;

    @MockBean
    private TransitionValidator transitionValidator;

    @Test
    void shouldPreserveExecutionIdAcrossDatabaseRead() {
        UUID orderId =
                UUID.fromString(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
                );

        UUID signalId =
                UUID.fromString(
                        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                );

        Order originalOrder =
                Order.createPendingExecution(
                        orderId,
                        "client-order-db-1",
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        new BigDecimal("0.001"),
                        new BigDecimal("77000"),
                        "SMA_STUB",
                        signalId
                );

        UUID originalExecutionId =
                originalOrder.getExecutionId();

        assertNotNull(
                originalExecutionId,
                "New PENDING_EXECUTION order must already have executionId"
        );

        OrderEntity entity =
                orderMapper.toEntity(originalOrder);

        orderRepository.saveAndFlush(entity);

        Order restoredOrder =
                orderRepositoryAdapter
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Order was not found after database save"
                                )
                        );

        assertNotNull(
                restoredOrder.getExecutionId(),
                "executionId must be restored from database"
        );

        assertEquals(
                originalExecutionId,
                restoredOrder.getExecutionId(),
                "executionId must remain identical after database read"
        );

        assertEquals(
                OrderStatus.PENDING_EXECUTION,
                restoredOrder.getStatus()
        );

        assertEquals(
                orderId,
                restoredOrder.getId()
        );

        assertEquals(
                signalId,
                restoredOrder.getSignalId()
        );
    }

    @Test
    void shouldPreserveExecutionIdWhenLoadingWithPessimisticLock() {
        UUID orderId =
                UUID.fromString(
                        "cccccccc-cccc-cccc-cccc-cccccccccccc"
                );

        UUID signalId =
                UUID.fromString(
                        "dddddddd-dddd-dddd-dddd-dddddddddddd"
                );

        Order originalOrder =
                Order.createPendingExecution(
                        orderId,
                        "client-order-db-2",
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        new BigDecimal("0.002"),
                        new BigDecimal("76000"),
                        "SMA_STUB",
                        signalId
                );

        UUID originalExecutionId =
                originalOrder.getExecutionId();

        assertNotNull(
                originalExecutionId,
                "New PENDING_EXECUTION order must already have executionId"
        );

        OrderEntity entity =
                orderMapper.toEntity(originalOrder);

        orderRepository.saveAndFlush(entity);

        ExecutionContext context =
                ExecutionContext.of(originalOrder);

        Order claimedOrder =
                orderRepositoryAdapter
                        .claimForExecution(
                                orderId,
                                context
                        )
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Order could not be claimed for execution"
                                )
                        );

        assertNotNull(
                claimedOrder.getExecutionId(),
                "executionId must survive pessimistic-lock read"
        );

        assertEquals(
                originalExecutionId,
                claimedOrder.getExecutionId(),
                "executionId loaded by findByIdForUpdate must equal original executionId"
        );

        assertEquals(
                context.attempt().executionId(),
                claimedOrder.getExecutionId(),
                "claim executionId must equal Order executionId"
        );

        assertEquals(
                OrderStatus.EXECUTING,
                claimedOrder.getStatus()
        );
    }
}