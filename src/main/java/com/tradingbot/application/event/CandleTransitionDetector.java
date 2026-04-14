package com.tradingbot.application.event;

import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Детектор перехода свечи в закрытое состояние.
 * <p>
 * Гарантирует, что каждая свеча будет обработана ровно один раз.
 * Использует {@link ConcurrentHashMap#compute} для потокобезопасности.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandleTransitionDetector {

    private final ConcurrentHashMap<String, Instant> lastProcessed = new ConcurrentHashMap<>();
    private final ProcessedCandleRepository processedCandleRepository;

    /**
     * Проверяет, является ли последняя свеча в окне новой закрытой свечой.
     *
     * @param window окно свечей
     * @return Optional с событием, если обнаружена новая закрытая свеча
     */
    public Optional<NewClosedCandleEvent> detect(CandleWindow window) {
        if (!window.isReady()) {
            return Optional.empty();
        }

        Candle lastCandle = window.getLast();
        String symbol = window.symbol();
        Instant openTime = lastCandle.openTime();

        AtomicBoolean isNew = new AtomicBoolean(false);

        lastProcessed.compute(symbol, (key, current) -> {
            if (current == null || !current.equals(openTime)) {
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
     * Сбрасывает состояние обработки для указанного символа.
     *
     * @param symbol торговый символ
     */
    public void reset(String symbol) {
        lastProcessed.remove(symbol);
        log.info("Reset processed state for symbol {}", symbol);
    }
}