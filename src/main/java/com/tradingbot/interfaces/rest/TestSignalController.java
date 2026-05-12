package com.tradingbot.interfaces.rest;

import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.common.enums.SignalType;import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
public class TestSignalController {

    private final ApplicationEventPublisher eventPublisher;

    public TestSignalController(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @GetMapping("/test-signal")
    public String sendTestSignal() {
        SignalEvent testEvent = new SignalEvent(
                java.util.UUID.randomUUID(),
                "BTCUSDT",
                SignalType.BUY,
                new BigDecimal("65000.00"),
                BigDecimal.ZERO, // quantity
                BigDecimal.ZERO, // stopLoss
                BigDecimal.ZERO, // takeProfit
                Instant.now(),
                "test-strategy"
        );
        
        eventPublisher.publishEvent(testEvent);        return "Тестовый сигнал отправлен в систему! Проверьте Telegram.";
    }}
