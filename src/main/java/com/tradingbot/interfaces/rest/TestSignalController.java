package com.tradingbot.interfaces.rest;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.model.Signal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Test signal endpoint — DEV ONLY.
 *
 * FIX: @Profile("dev") — this bean does not exist in prod, testnet, or live profiles.
 * FIX: Uses Signal.builder() with correct fields (was using a non-existent constructor).
 *
 * ⚠️ Never remove @Profile("dev") — this endpoint bypasses the strategy layer
 * and publishes a signal directly. In prod this is a financial security risk.
 */
@RestController
@Profile("dev")
public class TestSignalController {

    private final ApplicationEventPublisher eventPublisher;

    public TestSignalController(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @GetMapping("/dev/test-signal")
    public String sendTestSignal(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "BUY") OrderSide side,
            @RequestParam(defaultValue = "65000.00") String price) {

        Signal testSignal = Signal.builder()
                .clientOrderId(UUID.randomUUID().toString())
                .symbol(symbol)
                .side(side)
                .price(new BigDecimal(price))
                .quantity(null) // Risk Engine will size
                .strategyId("test-controller")
                .generatedAt(Instant.now())
                .build();

        eventPublisher.publishEvent(testSignal);

        return String.format("[DEV] Test signal published: %s %s @ %s (id=%s)",
                side, symbol, price, testSignal.getClientOrderId());
    }
}