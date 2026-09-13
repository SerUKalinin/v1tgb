package com.tradingbot.application.strategy;

import com.tradingbot.application.event.NewClosedCandleEvent;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.tracing.IdentityFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Тестовая реализация торгового движка стратегии.
 *
 * <p>Используется только в profile = test для:
 * <ul>
 *     <li>проверки event pipeline</li>
 *     <li>валидации SignalRouter</li>
 *     <li>нагрузочного и интеграционного тестирования</li>
 * </ul>
 *
 * <p>Генерирует упрощённые BUY сигналы с throttling по символу.</p>
 */
@Slf4j
@Component
@Profile("test")
public class TestStrategyEngine implements StrategyEngine {

    /**
     * Кулдаун между сигналами по одному символу.
     */
    private static final Duration COOLDOWN = Duration.ofMinutes(15);

    /**
     * Последнее время генерации сигнала по символу.
     */
    private final Map<String, Instant> lastSignalTimes = new ConcurrentHashMap<>();

    /**
     * Выполняет генерацию тестового сигнала.
     *
     * <p>Поведение:
     * <ul>
     *     <li>подавляет сигналы чаще COOLDOWN</li>
     *     <li>всегда генерирует BUY сигнал</li>
     * </ul>
     *
     * @param candle закрытая свеча
     * @return optional тестового сигнала
     */
    @Override
    public Optional<SignalEvent> evaluate(NewClosedCandleEvent candle) {

        Instant now = Instant.now();
        Instant last = lastSignalTimes.get(candle.symbol());

        if (last != null && Duration.between(last, now).compareTo(COOLDOWN) < 0) {
            log.debug("[STRATEGY] throttled {}", candle.symbol());
            return Optional.empty();
        }

        lastSignalTimes.put(candle.symbol(), now);

        UUID signalId = IdentityFactory.derive(
                UUID.nameUUIDFromBytes(candle.symbol().getBytes()),
                "test-signal-" + candle.closeTime()
        );

        SignalEvent signal = new SignalEvent(
                signalId,
                candle.symbol(),
                SignalType.BUY,
                candle.close(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                candle.closeTime(),
                "TEST_STRATEGY"
        );

        return Optional.of(signal);
    }
}