package com.tradingbot.application.event;

import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Детектор перехода свечи в закрытое состояние.
 * Гарантирует, что каждая свеча будет обработана ровно один раз.
 * <p>
 * Использует атомарный {@link ConcurrentHashMap#compute} для предотвращения
 * race condition при одновременных вызовах из нескольких потоков.
 * <p>
 * Поддерживает заглушку для будущей персистентной проверки (БД/Redis).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandleTransitionDetector {

    // In-memory хранилище для отслеживания обработанных свечей
    private final ConcurrentHashMap<String, Instant> lastProcessed = new ConcurrentHashMap<>();

    // Интерфейс-заглушка для персистентности (пока возвращает true)
    private final ProcessedCandleRepository processedCandleRepository;

    /**
     * Проверяет, является ли последняя свеча в окне новой закрытой свечой,
     * и если да — возвращает событие с полным снепшотом окна.
     *
     * @param window текущее окно свечей (должно быть готово: isReady() == true)
     * @return Optional с событием, если свеча новая и закрытая, иначе empty
     */
    public Optional<NewClosedCandleEvent> detect(CandleWindow window) {
        if (!window.isReady()) {
            return Optional.empty();
        }

        Candle lastCandle = window.getLast();
        String symbol = window.symbol();
        Instant openTime = lastCandle.openTime();

        java.util.concurrent.atomic.AtomicBoolean isNew = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Атомарно проверяем и обновляем состояние
        lastProcessed.compute(symbol, (key, current) -> {
            if (current == null || !current.equals(openTime)) {
                // Проверяем через репозиторий (заглушку), чтобы исключить дубли после перезапуска
                if (processedCandleRepository.markAsProcessed(symbol, openTime)) {
                    isNew.set(true);
                    return openTime;
                }
            }
            return current;
        });

        if (isNew.get()) {
            log.info("[DETECTOR] New closed candle detected: symbol={} openTime={}", symbol, openTime);
            return Optional.of(new NewClosedCandleEvent(symbol, lastCandle, window));
        }

        return Optional.empty();
    }
    /**
     * Сброс состояния для конкретного символа (например, при смене таймфрейма).
     */
    public void reset(String symbol) {
        lastProcessed.remove(symbol);
        log.info("Reset processed state for symbol {}", symbol);
    }
}