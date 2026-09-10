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
 *
 * Отвечает за определение факта появления новой закрытой свечи
 * в потоке рыночных данных.
 *
 * Гарантирует, что каждая свеча будет обработана строго один раз,
 * даже при конкурентной обработке потоков.
 *
 * Использует комбинацию:
 * - in-memory cache (ConcurrentHashMap)
 * - внешнего репозитория идемпотентности (ProcessedCandleRepository)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandleTransitionDetector {

    /**
     * Кэш последних обработанных свечей по символу.
     *
     * Key: торговый символ (например BTCUSDT)
     * Value: openTime последней обработанной свечи
     */
    private final ConcurrentHashMap<String, Instant> lastProcessed = new ConcurrentHashMap<>();

    /**
     * Репозиторий идемпотентности для гарантии,
     * что свеча не будет обработана повторно даже после рестарта.
     */
    private final ProcessedCandleRepository processedCandleRepository;

    /**
     * Проверяет, является ли последняя свеча в окне новой закрытой свечой.
     *
     * Алгоритм:
     * 1. Проверка готовности окна
     * 2. Получение последней свечи
     * 3. Сверка с in-memory состоянием
     * 4. Фиксация через persistent repository (идемпотентность)
     * 5. Генерация события при первом обнаружении
     *
     * @param window окно свечей
     * @return Optional события новой закрытой свечи
     */
    public Optional<NewClosedCandleEvent> detect(CandleWindow window) {
        if (!window.isReady()) {
            return Optional.empty();
        }

        Candle lastCandle = window.getLast();
        String symbol = window.getSymbol();
        Instant openTime = lastCandle.getOpenTime();

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
            return Optional.of(new NewClosedCandleEvent(
                    symbol,
                    lastCandle.getOpen(),
                    lastCandle.getHigh(),
                    lastCandle.getLow(),
                    lastCandle.getClose(),
                    lastCandle.getVolume(),
                    lastCandle.getCloseTime()
            ));
        }

        return Optional.empty();
    }

    /**
     * Сбрасывает in-memory состояние обработки для указанного символа.
     *
     * Используется при:
     * - пересинхронизации рынка
     * - холодном старте
     * - сбросе состояния стратегии
     *
     * @param symbol торговый символ
     */
    public void reset(String symbol) {
        lastProcessed.remove(symbol);
        log.info("Reset processed state for symbol {}", symbol);
    }
}