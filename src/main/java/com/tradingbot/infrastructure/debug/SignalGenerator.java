//package com.tradingbot.infrastructure.debug;
//
//import com.tradingbot.common.enums.SignalType;
//import com.tradingbot.domain.event.SignalEvent;
//import com.tradingbot.tracing.IdentityFactory;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.context.ApplicationEventPublisher;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Component;
//
//import java.math.BigDecimal;
//import java.time.Instant;
//import java.util.Random;
//import java.util.UUID;
//
///**
// * Генератор тестовых торговых сигналов.
// *
// * <p>Используется для проверки end-to-end цепочки обработки:
// * Strategy → Risk → Execution → Outbox → Telegram/Monitoring.
// *
// * <p>Предназначен только для debug/dev сценариев и не должен использоваться
// * в production окружении.
// */
//@Slf4j
//@RequiredArgsConstructor
//@org.springframework.context.annotation.Profile("none") // Отключено, чтобы не мешать реальным сигналам
//public class SignalGenerator {
//
//    private final ApplicationEventPublisher eventPublisher;
//    private final Random random = new Random();
//
//    private final String[] symbols = {"BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"};
//
//    /**
//     * Периодически генерирует фиктивные торговые сигналы.
//     *
//     * <p>Интервал: 15 секунд.
//     * <p>Используется для проверки корректности обработки сигналов
//     * во всей системе.
//     */
//    @Scheduled(fixedRate = 15000)
//    public void generateFakeSignal() {
//        String symbol = symbols[random.nextInt(symbols.length)];
//        SignalType type = random.nextBoolean() ? SignalType.BUY : SignalType.SELL;
//        BigDecimal price = BigDecimal.valueOf(30000 + random.nextDouble() * 1000);
//
//        UUID signalId = IdentityFactory.derive(
//                UUID.nameUUIDFromBytes("debug-generator".getBytes()),
//                "signal-" + System.nanoTime()
//        );
//
//        SignalEvent event = new SignalEvent(
//                signalId,
//                symbol,
//                type,
//                price,
//                BigDecimal.ZERO,
//                BigDecimal.ZERO,
//                BigDecimal.ZERO,
//                Instant.now(),
//                "DEBUG-STRATEGY"
//        );
//
//        log.info("[DEBUG] Generating fake signal: {} {} at {}", type, symbol, price);
//        eventPublisher.publishEvent(event);
//    }
//}