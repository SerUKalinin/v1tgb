package com.tradingbot.domain.event;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import com.tradingbot.tracing.IdentityFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DomainEventIdentityTest {

    @Test
    void shouldDeriveSignalEventIdFromCanonicalExecutionIdentity() {
        UUID signalId =
                UUID.fromString("11111111-1111-1111-1111-111111111111");

        UUID orderId =
                IdentityFactory.deriveOrder(signalId);

        UUID executionId =
                IdentityFactory.deriveExecution(orderId, 1);

        SignalEvent event = new SignalEvent(
                signalId,
                "BTCUSDT",
                SignalType.BUY,
                new BigDecimal("77000"),
                new BigDecimal("0.001"),
                new BigDecimal("76000"),
                new BigDecimal("78000"),
                Instant.parse("2026-09-15T11:00:00Z"),
                "SMA_STUB"
        );

        UUID expectedEventId =
                IdentityFactory.deriveEventId(
                        executionId,
                        "SIGNAL_RECEIVED"
                );

        System.out.println("========== SIGNAL EVENT IDENTITY ==========");
        System.out.println("signalId             = " + signalId);
        System.out.println("orderId              = " + orderId);
        System.out.println("EXPECTED executionId = " + executionId);
        System.out.println(
                "ACTUAL executionId   = " +
                        event.getAttempt().executionId()
        );
        System.out.println("EXPECTED eventId     = " + expectedEventId);
        System.out.println("ACTUAL eventId       = " + event.getEventId());
        System.out.println("eventType            = " + event.getEventType());
        System.out.println("===========================================");

        assertEquals(
                executionId,
                event.getAttempt().executionId(),
                "SignalEvent must contain canonical executionId derived from orderId"
        );

        assertEquals(
                "SIGNAL_RECEIVED",
                event.getEventType()
        );

        assertEquals(
                expectedEventId,
                event.getEventId(),
                "SignalEvent.eventId must be derived from executionId + eventType"
        );
    }

    @Test
    void shouldDeriveOrderFilledEventIdFromExecutionIdAndEventType() {
        UUID signalId =
                UUID.fromString("22222222-2222-2222-2222-222222222222");

        UUID orderId =
                IdentityFactory.deriveOrder(signalId);

        UUID executionId =
                IdentityFactory.deriveExecution(orderId, 1);

        IdentityContext identity =
                IdentityContext.of(signalId);

        ExecutionAttemptContext attempt =
                ExecutionAttemptContext.recover(
                        signalId,
                        executionId,
                        1
                );

        OrderFilledEvent event = new OrderFilledEvent(
                identity,
                attempt,
                BusinessContext.empty(),
                orderId,
                "external-123",
                "BTCUSDT",
                new BigDecimal("0.001"),
                new BigDecimal("77000")
        );

        UUID expectedEventId =
                IdentityFactory.deriveEventId(
                        executionId,
                        "ORDER_FILLED"
                );

        assertEquals(
                executionId,
                event.getAttempt().executionId(),
                "OrderFilledEvent must contain the supplied executionId"
        );

        assertEquals(
                "ORDER_FILLED",
                event.getEventType()
        );

        assertEquals(
                expectedEventId,
                event.getEventId(),
                "OrderFilledEvent.eventId must be derived from executionId + eventType"
        );
    }

    @Test
    void shouldDeriveTradeCreatedEventIdFromExecutionIdAndEventType() {
        UUID signalId =
                UUID.fromString("33333333-3333-3333-3333-333333333333");

        UUID orderId =
                IdentityFactory.deriveOrder(signalId);

        UUID executionId =
                IdentityFactory.deriveExecution(orderId, 1);

        UUID tradeId =
                IdentityFactory.deriveEventId(
                        executionId,
                        "trade"
                );

        IdentityContext identity =
                IdentityContext.of(signalId);

        ExecutionAttemptContext attempt =
                ExecutionAttemptContext.recover(
                        signalId,
                        executionId,
                        1
                );

        TradeCreatedEvent event = new TradeCreatedEvent(
                identity,
                attempt,
                BusinessContext.empty(),
                tradeId,
                orderId,
                "BTCUSDT",
                "SMA_STUB",
                new BigDecimal("0.001"),
                new BigDecimal("77000"),
                OrderSide.BUY,
                new BigDecimal("76000"),
                new BigDecimal("78000")
        );

        UUID expectedEventId =
                IdentityFactory.deriveEventId(
                        executionId,
                        "TRADE_CREATED"
                );

        assertEquals(
                executionId,
                event.getAttempt().executionId(),
                "TradeCreatedEvent must contain the supplied executionId"
        );

        assertEquals(
                "TRADE_CREATED",
                event.getEventType()
        );

        assertEquals(
                expectedEventId,
                event.getEventId(),
                "TradeCreatedEvent.eventId must be derived from executionId + eventType"
        );
    }
}