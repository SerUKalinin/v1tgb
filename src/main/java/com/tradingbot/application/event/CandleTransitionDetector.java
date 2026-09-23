package com.tradingbot.application.event;

import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Детектор перехода свечи в закрытое состояние.
 *
 * <p>Отвечает за определение появления новой фактически закрытой свечи
 * в потоке рыночных данных.</p>
 *
 * <p>Критически важно:
 * detect() только обнаруживает новую свечу.
 * Факт успешной обработки свечи фиксируется отдельным
 * markAsProcessed() ПОСЛЕ успешного прохождения downstream pipeline.</p>
 *
 * <p>Это предотвращает ситуацию:
 *
 * <pre>
 * candle marked processed
 *        ↓
 * signal pipeline failed
 *        ↓
 * candle permanently lost
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandleTransitionDetector {

    /**
     * Последняя успешно обработанная закрытая свеча по символу.
     *
     * Key:
     *     торговый символ
     *
     * Value:
     *     openTime последней успешно обработанной свечи
     */
    private final ConcurrentHashMap<String, Instant> lastProcessed =
            new ConcurrentHashMap<>();

    /**
     * Репозиторий фиксации успешно обработанных свечей.
     */
    private final ProcessedCandleRepository processedCandleRepository;

    /**
     * Инициализирует baseline после warm-up.
     *
     * <p>Последняя историческая закрытая свеча не должна
     * немедленно породить новый сигнал при первом refresh.</p>
     *
     * @param symbol   торговый символ
     * @param openTime openTime последней закрытой свечи
     */
    public void prime(
            String symbol,
            Instant openTime
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException(
                    "symbol не должен быть пустым"
            );
        }

        if (openTime == null) {
            throw new IllegalArgumentException(
                    "openTime не должен быть null"
            );
        }

        lastProcessed.merge(
                symbol,
                openTime,
                (current, candidate) ->
                        candidate.isAfter(current)
                                ? candidate
                                : current
        );

        log.info(
                "[DETECTOR] Primed baseline: symbol={} openTime={}",
                symbol,
                openTime
        );
    }

    /**
     * Ищет последнюю фактически закрытую свечу
     * и сообщает о ней как о новой, если она ещё
     * не была успешно обработана.
     *
     * <p>ВАЖНО:
     * этот метод НЕ записывает свечу как processed.</p>
     *
     * @param window окно свечей
     * @return событие новой закрытой свечи
     */
    public Optional<NewClosedCandleEvent> detect(
            CandleWindow window
    ) {
        if (window == null || !window.isReady()) {
            return Optional.empty();
        }

        String symbol = window.getSymbol();

        Optional<Candle> latestClosed =
                window.getCandles()
                        .stream()
                        .filter(Candle::isClosed)
                        .max(
                                Comparator.comparing(
                                        Candle::getOpenTime
                                )
                        );

        if (latestClosed.isEmpty()) {
            log.debug(
                    "[DETECTOR] No closed candle available: symbol={}",
                    symbol
            );

            return Optional.empty();
        }

        Candle candle = latestClosed.get();
        Instant openTime = candle.getOpenTime();

        Instant current =
                lastProcessed.get(symbol);

        /*
         * Свеча уже успешно обработана
         * или является более старой.
         */
        if (current != null
                && !openTime.isAfter(current)) {
            return Optional.empty();
        }

        /*
         * Только detection.
         *
         * Никакого persistent ACK здесь нет.
         */
        return Optional.of(
                new NewClosedCandleEvent(
                        symbol,
                        candle.getOpenTime(),
                        candle.getOpen(),
                        candle.getHigh(),
                        candle.getLow(),
                        candle.getClose(),
                        candle.getVolume(),
                        candle.getCloseTime()
                )
        );
    }

    /**
     * Подтверждает успешную обработку закрытой свечи.
     *
     * <p>Вызывается только ПОСЛЕ того, как downstream pipeline
     * успешно обработал NewClosedCandleEvent.</p>
     *
     * <p>Если persistent repository не подтверждает запись,
     * in-memory baseline также не продвигается.</p>
     *
     * @param symbol   торговый символ
     * @param openTime openTime успешно обработанной свечи
     */
    public void markAsProcessed(
            String symbol,
            Instant openTime
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException(
                    "symbol не должен быть пустым"
            );
        }

        if (openTime == null) {
            throw new IllegalArgumentException(
                    "openTime не должен быть null"
            );
        }

        boolean marked =
                processedCandleRepository.markAsProcessed(
                        symbol,
                        openTime
                );

        if (!marked) {
            /*
             * Репозиторий уже знает эту свечу.
             *
             * Это нормально при повторной доставке:
             * состояние всё равно можно считать processed.
             */
            lastProcessed.compute(
                    symbol,
                    (key, current) ->
                            current == null
                                    || openTime.isAfter(current)
                                    ? openTime
                                    : current
            );

            return;
        }

        lastProcessed.compute(
                symbol,
                (key, current) ->
                        current == null
                                || openTime.isAfter(current)
                                ? openTime
                                : current
        );

        log.info(
                "[DETECTOR] Candle ACK: symbol={} openTime={}",
                symbol,
                openTime
        );
    }

    /**
     * Сбрасывает in-memory состояние для символа.
     *
     * <p>Persistent idempotency state при этом не удаляется.</p>
     *
     * @param symbol торговый символ
     */
    public void reset(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }

        lastProcessed.remove(symbol);

        log.info(
                "[DETECTOR] Reset in-memory state: symbol={}",
                symbol
        );
    }
}