package com.tradingbot.application.service.execution;

import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.IdentityContext;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionIdentityRetrySemanticsTest {

    @Test
    void transportRetryMustPreserveExecutionIdentity() {

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        ExecutionAttemptContext initialAttempt =
                ExecutionAttemptContext.firstAttemptForOrder(
                        orderId
                );

        ExecutionAttemptContext transportRetry1 =
                initialAttempt.withTransportRetry();

        ExecutionAttemptContext transportRetry2 =
                transportRetry1.withTransportRetry();

        assertThat(initialAttempt.executionId())
                .isNotNull();

        assertThat(transportRetry1.executionId())
                .as("transport retry must preserve executionId")
                .isEqualTo(
                        initialAttempt.executionId()
                );

        assertThat(transportRetry2.executionId())
                .as("repeated transport retry must preserve executionId")
                .isEqualTo(
                        initialAttempt.executionId()
                );

        assertThat(transportRetry1.attemptNumber())
                .isEqualTo(
                        initialAttempt.attemptNumber() + 1
                );

        assertThat(transportRetry2.attemptNumber())
                .isEqualTo(
                        initialAttempt.attemptNumber() + 2
                );
    }

    @Test
    void businessRetryMustCreateNewExecutionIdentity() {

        UUID orderId =
                UUID.randomUUID();

        ExecutionAttemptContext initialAttempt =
                ExecutionAttemptContext.firstAttemptForOrder(
                        orderId
                );

        ExecutionAttemptContext businessRetry =
                initialAttempt.nextBusinessAttempt();

        assertThat(businessRetry.executionId())
                .as("business retry must create a new executionId")
                .isNotEqualTo(
                        initialAttempt.executionId()
                );

        assertThat(businessRetry.attemptNumber())
                .isEqualTo(
                        initialAttempt.attemptNumber() + 1
                );

        assertThat(businessRetry.causationId())
                .as("business retry must preserve lifecycle causation/root identity")
                .isEqualTo(
                        initialAttempt.causationId()
                );
    }

    @Test
    void businessRetryIdentityMustBeDeterministic() {

        UUID orderId =
                UUID.randomUUID();

        ExecutionAttemptContext initialAttempt =
                ExecutionAttemptContext.firstAttemptForOrder(
                        orderId
                );

        UUID firstBusinessRetryId =
                initialAttempt
                        .nextBusinessAttempt()
                        .executionId();

        UUID secondBusinessRetryId =
                initialAttempt
                        .nextBusinessAttempt()
                        .executionId();

        assertThat(secondBusinessRetryId)
                .as("same business attempt must derive the same executionId")
                .isEqualTo(
                        firstBusinessRetryId
                );
    }

    @Test
    void executionContextTransportAndBusinessRetryMustKeepIdentityRules() {

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        ExecutionAttemptContext initialAttempt =
                ExecutionAttemptContext.firstAttemptForOrder(
                        orderId
                );

        ExecutionContext initialContext =
                ExecutionContext.of(
                        new IdentityContext(
                                signalId,
                                signalId
                        ),
                        initialAttempt,
                        BusinessContext.of(
                                orderId.toString()
                        )
                );

        ExecutionContext transportRetry =
                initialContext.withTransportRetry();

        ExecutionContext businessRetry =
                initialContext.nextBusinessAttempt();

        assertThat(transportRetry.attempt().executionId())
                .as("ExecutionContext transport retry must preserve executionId")
                .isEqualTo(
                        initialContext.attempt().executionId()
                );

        assertThat(businessRetry.attempt().executionId())
                .as("ExecutionContext business retry must create new executionId")
                .isNotEqualTo(
                        initialContext.attempt().executionId()
                );

        assertThat(businessRetry.business())
                .isEqualTo(
                        initialContext.business()
                );

        assertThat(businessRetry.identity())
                .isEqualTo(
                        initialContext.identity()
                );
    }
}