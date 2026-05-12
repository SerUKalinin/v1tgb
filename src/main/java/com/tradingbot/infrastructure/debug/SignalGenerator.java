package com.tradingbot.infrastructure.debug;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Random;

/**
 * Генератор тестовых сигналов для проверки работы Product Layer и Telegram.
 */
//@Component
@Slf4j
@RequiredArgsConstructor
@org.springframework.context.annotation.Profile("none") // Отключено, чтобы не мешать реальным сигналам
public class SignalGenerator {
    private final ApplicationEventPublisher eventPublisher;
    private final Random random = new Random();
    private final String[] symbols = {"BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"};

    @Scheduled(fixedRate = 15000) // Каждые 15 секунд
    public void generateFakeSignal() {
        String symbol = symbols[random.nextInt(symbols.length)];
        SignalType type = random.nextBoolean() ? SignalType.BUY : SignalType.SELL;
        BigDecimal price = BigDecimal.valueOf(30000 + random.nextDouble() * 1000);

        SignalEvent event = new SignalEvent(
                java.util.UUID.randomUUID(),
                symbol,
                type,
                price,
                BigDecimal.ZERO, // quantity
                BigDecimal.ZERO, // stopLoss
                BigDecimal.ZERO, // takeProfit
                Instant.now(),
                "DEBUG-STRATEGY"
        );

        log.info("[DEBUG] Generating fake signal: {} {} at {}", type, symbol, price);        eventPublisher.publishEvent(event);
    }
}
