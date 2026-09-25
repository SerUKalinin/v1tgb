package com.tradingbot.application.service.execution;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.adapter.OrderRepositoryAdapter;
import com.tradingbot.testutil.TestOrderFactory;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ExecutionIdentityPersistenceGuardTest extends BaseIntegrationTest {

    @Autowired
    private OrderRepositoryAdapter orderRepositoryAdapter;

    @Autowired
    private com.tradingbot.infrastructure.persistence.repository.OrderRepository orderRepository;

    @Autowired
    private com.tradingbot.infrastructure.persistence.mapper.OrderMapper orderMapper;

    private TestOrderFactory orderFactory;

    @BeforeEach
    void setUp() {
        orderFactory = new TestOrderFactory(
                orderRepository,
                orderMapper
        );
    }

    @Test
    void foreignExecutionIdMustNotClaimPersistedOrder() {

        Order persistedOrder =
                orderFactory.create(
                        OrderStatus.PENDING_EXECUTION
                );

        UUID orderId =
                persistedOrder.getId();

        UUID canonicalExecutionId =
                persistedOrder.getExecutionId();

        UUID foreignExecutionId =
                UUID.randomUUID();

        ExecutionContext foreignContext =
                new ExecutionContext(
                        new IdentityContext(
                                persistedOrder.getSignalId(),
                                persistedOrder.getSignalId()
                        ),
                        ExecutionAttemptContext.recover(
                                UUID.randomUUID(),
                                foreignExecutionId,
                                1
                        ),
                        BusinessContext.of(
                                orderId.toString()
                        )
                );

        Optional<Order> claim =
                orderRepositoryAdapter.claimForExecution(
                        orderId,
                        foreignContext
                );

        assertThat(claim)
                .as("foreign execution must not claim persisted lifecycle")
                .isEmpty();

        Order finalState =
                orderRepositoryAdapter.findById(orderId)
                        .orElseThrow();

        assertThat(finalState.getStatus())
                .as("foreign execution must not change order lifecycle")
                .isEqualTo(OrderStatus.PENDING_EXECUTION);

        assertThat(finalState.getExecutionId())
                .as("canonical persisted executionId must remain unchanged")
                .isEqualTo(canonicalExecutionId);

        assertThat(finalState.getExecutionId())
                .isNotEqualTo(foreignExecutionId);

        assertThat(finalState.getExecutionAttempts())
                .as("foreign execution must not increment execution attempts")
                .isZero();
    }
}