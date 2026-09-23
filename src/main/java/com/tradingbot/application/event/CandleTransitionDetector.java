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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Детектор перехода свечи в закрытое состояние.
 *
 * <p>Отвечает за определение появления новой фактически закрытой свечи
 * в потоке рыночных данных.
 *
 * <p>Гарантирует идемпотентную обработку свечи:
 * одна свеча с одним openTime не должна приводить
 * к повторной генерации события.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandleTransitionDetector {

    /**
     * Последняя обработанная закрытая свеча по символу.
     *
     * Key:
     *     торговый символ
     *
     * Value:
     *     openTime последней обработанной свечи
     */
    private final ConcurrentHashMap<String, Instant> lastProcessed =
            new ConcurrentHashMap<>();

    /**
     * Репозиторий идемпотентности.
     */
    private final ProcessedCandleRepository processedCandleRepository;

    /**
     * Инициализирует baseline после warm-up.
     *
     * <p>Последняя уже закрытая историческая свеча
     * не должна повторно породить NewClosedCandleEvent
     * только потому, что система впервые начала работать.
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
     * и генерирует событие только при её новом появлении.
     *
     * <p>Текущая формирующаяся свеча игнорируется,
     * даже если она является последней свечой окна.
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

        Optional<Candle> latestClosed = window.getCandles().stream()
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

        AtomicBoolean isNew = new AtomicBoolean(false);

        lastProcessed.compute(
                symbol,
                (key, current) -> {

                    /*
                     * Уже обработанная или более старая свеча.
                     */
                    if (current != null
                            && !openTime.isAfter(current)) {
                        return current;
                    }

                    /*
                     * Persistent idempotency boundary.
                     */
                    if (processedCandleRepository.markAsProcessed(
                            symbol,
                            openTime
                    )) {
                        isNew.set(true);
                    }

                    return openTime;
                }
        );

        if (!isNew.get()) {
            return Optional.empty();
        }

        log.info(
                "[DETECTOR] New closed candle detected: symbol={} openTime={} closeTime={}",
                symbol,
                candle.getOpenTime(),
                candle.getCloseTime()
        );

        return Optional.of(
                new NewClosedCandleEvent(
                        symbol,
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
     * Сбрасывает in-memory состояние обработки для символа.
     *
     * @param symbol торговый символ
     */
    public void reset(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }

        lastProcessed.remove(symbol);

        log.info(
                "Reset processed state for symbol {}",
                symbol
        );
    }
}