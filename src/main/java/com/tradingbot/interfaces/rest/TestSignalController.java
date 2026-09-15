package com.tradingbot.interfaces.rest;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.tracing.IdentityFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
public class TestSignalController {

    private static final String SYMBOL = "BTCUSDT";
    private static final String STRATEGY_ID = "test-strategy";

    private final ApplicationEventPublisher eventPublisher;

    public TestSignalController(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @GetMapping("/test-signal")
    public String sendTestBuySignal() {

        UUID signalId = IdentityFactory.derive(
                UUID.nameUUIDFromBytes("rest-api".getBytes()),
                "signal-buy-" + System.nanoTime()
        );

        SignalEvent testEvent = new SignalEvent(
                signalId,
                SYMBOL,
                SignalType.BUY,
                new BigDecimal("65000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now(),
                STRATEGY_ID
        );

        eventPublisher.publishEvent(testEvent);

        return "Тестовый BUY сигнал отправлен в систему!";
    }

    @GetMapping("/test-sell")
    public String sendTestSellSignal() {

        UUID signalId = IdentityFactory.derive(
                UUID.nameUUIDFromBytes("rest-api".getBytes()),
                "signal-sell-" + System.nanoTime()
        );

        SignalEvent testEvent = new SignalEvent(
                signalId,
                SYMBOL,
                SignalType.SELL,
                new BigDecimal("77700.01"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now(),
                STRATEGY_ID
        );

        eventPublisher.publishEvent(testEvent);

        return "Тестовый SELL сигнал отправлен в систему!";
    }
}