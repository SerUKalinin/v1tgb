package com.tradingbot.tracing;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ExecutionAttemptContextTest {

    @Test
    void nextStepMustKeepSameExecutionId() {
        UUID executionId = UUID.randomUUID();
        UUID causationId = UUID.randomUUID();
        UUID nextCausationId = UUID.randomUUID();

        ExecutionAttemptContext initial =
                ExecutionAttemptContext.recover(
                        causationId,
                        executionId,
                        1
                );

        ExecutionAttemptContext next =
                initial.nextStep(nextCausationId);

        assertEquals(
                executionId,
                next.executionId(),
                "executionId must remain immutable within one execution lifecycle"
        );

        assertEquals(
                nextCausationId,
                next.causationId()
        );

        assertEquals(
                initial.attemptNumber(),
                next.attemptNumber()
        );

        assertNotEquals(
                next.executionId(),
                next.causationId(),
                "executionId must never equal causationId"
        );
    }

    @Test
    void transportRetryMustKeepSameExecutionId() {
        UUID executionId = UUID.randomUUID();
        UUID causationId = UUID.randomUUID();

        ExecutionAttemptContext initial =
                ExecutionAttemptContext.recover(
                        causationId,
                        executionId,
                        1
                );

        ExecutionAttemptContext retry =
                initial.withTransportRetry();

        assertEquals(
                executionId,
                retry.executionId()
        );

        assertEquals(
                causationId,
                retry.causationId()
        );

        assertEquals(
                2,
                retry.attemptNumber()
        );
    }
}