package com.tradingbot.tracing;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionIdDeterminismTest {

    @Test
    void testExecutionIdIsDeterministic() {
        UUID orderId = UUID.randomUUID();
        int attempt = 1;

        UUID execId1 = IdentityFactory.deriveExecution(orderId, attempt);
        UUID execId2 = IdentityFactory.deriveExecution(orderId, attempt);

        assertEquals(execId1, execId2, "ExecutionId must be deterministic for same order and attempt");
        assertNotEquals(orderId, execId1, "ExecutionId must not be equal to OrderId");
    }

    @Test
    void testExecutionIdChangesWithAttempt() {
        UUID orderId = UUID.randomUUID();

        UUID execId1 = IdentityFactory.deriveExecution(orderId, 1);
        UUID execId2 = IdentityFactory.deriveExecution(orderId, 2);

        assertNotEquals(execId1, execId2, "ExecutionId must change with attempt number");
    }

    @Test
    void testExecutionContextOfOrderIsDeterministic() {
        UUID signalId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        
        // Mock-like behavior for Order (simplified)
        com.tradingbot.domain.model.Order order = com.tradingbot.domain.model.Order.builder()
                .id(orderId)
                .signalId(signalId)
                .executionAttempts(1)
                .build();

        ExecutionContext ctx1 = ExecutionContext.of(order);
        ExecutionContext ctx2 = ExecutionContext.of(order);

        assertEquals(ctx1.attempt().executionId(), ctx2.attempt().executionId());
        assertEquals(IdentityFactory.deriveExecution(orderId, 1), ctx1.attempt().executionId());
    }
}
