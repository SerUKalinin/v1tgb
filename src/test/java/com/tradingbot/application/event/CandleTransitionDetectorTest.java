package com.tradingbot.application.event;

import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests для lifecycle acknowledgement
 * закрытых market candles.
 */
class CandleTransitionDetectorTest {

    @Test
    void shouldNotMarkCandleDuringDetection() {

        RecordingProcessedCandleRepository repository =
                new RecordingProcessedCandleRepository();

        CandleTransitionDetector detector =
                new CandleTransitionDetector(
                        repository
                );

        Candle candle =
                closedCandle(
                        "2026-09-23T12:00:00Z",
                        "2026-09-23T12:00:59Z"
                );

        CandleWindow window =
                new CandleWindow(
                        "BTCUSDT",
                        List.of(candle)
                );

        assertTrue(
                detector.detect(window).isPresent()
        );

        assertEquals(
                0,
                repository.markCalls,
                "detect() не должен ACK-ать candle"
        );
    }

    @Test
    void shouldAllowRetryWhenProcessingWasNotAcknowledged() {

        RecordingProcessedCandleRepository repository =
                new RecordingProcessedCandleRepository();

        CandleTransitionDetector detector =
                new CandleTransitionDetector(
                        repository
                );

        Candle candle =
                closedCandle(
                        "2026-09-23T12:01:00Z",
                        "2026-09-23T12:01:59Z"
                );

        CandleWindow window =
                new CandleWindow(
                        "BTCUSDT",
                        List.of(candle)
                );

        /*
         * First detection.
         *
         * Processing is intentionally not ACK-ed.
         */
        assertTrue(
                detector.detect(window).isPresent()
        );

        /*
         * Second detection must still see
         * the candle as unprocessed.
         */
        assertTrue(
                detector.detect(window).isPresent(),
                "Без ACK свеча должна оставаться повторно обрабатываемой"
        );
    }

    @Test
    void shouldSuppressCandleAfterSuccessfulAcknowledgement() {

        RecordingProcessedCandleRepository repository =
                new RecordingProcessedCandleRepository();

        CandleTransitionDetector detector =
                new CandleTransitionDetector(
                        repository
                );

        Candle candle =
                closedCandle(
                        "2026-09-23T12:02:00Z",
                        "2026-09-23T12:02:59Z"
                );

        CandleWindow window =
                new CandleWindow(
                        "BTCUSDT",
                        List.of(candle)
                );

        var event =
                detector
                        .detect(window)
                        .orElseThrow();

        detector.markAsProcessed(
                event.symbol(),
                event.openTime()
        );

        assertTrue(
                detector.detect(window).isEmpty(),
                "После ACK candle не должна генерировать событие повторно"
        );

        assertEquals(
                1,
                repository.markCalls
        );
    }

    @Test
    void shouldIgnoreFormingCandle() {

        RecordingProcessedCandleRepository repository =
                new RecordingProcessedCandleRepository();

        CandleTransitionDetector detector =
                new CandleTransitionDetector(
                        repository
                );

        Candle formingCandle =
                Candle.builder()
                        .symbol("BTCUSDT")
                        .open(new BigDecimal("100"))
                        .high(new BigDecimal("101"))
                        .low(new BigDecimal("99"))
                        .close(new BigDecimal("100.5"))
                        .volume(new BigDecimal("10"))
                        .openTime(
                                Instant.parse(
                                        "2026-09-23T12:03:00Z"
                                )
                        )
                        .closeTime(
                                Instant.parse(
                                        "2026-09-23T12:03:59Z"
                                )
                        )
                        .isClosed(false)
                        .build();

        CandleWindow window =
                new CandleWindow(
                        "BTCUSDT",
                        List.of(formingCandle)
                );

        assertTrue(
                detector.detect(window).isEmpty()
        );

        assertEquals(
                0,
                repository.markCalls
        );
    }

    private Candle closedCandle(
            String openTime,
            String closeTime
    ) {
        return Candle.builder()
                .symbol("BTCUSDT")
                .open(new BigDecimal("100"))
                .high(new BigDecimal("101"))
                .low(new BigDecimal("99"))
                .close(new BigDecimal("100.5"))
                .volume(new BigDecimal("10"))
                .openTime(
                        Instant.parse(openTime)
                )
                .closeTime(
                        Instant.parse(closeTime)
                )
                .isClosed(true)
                .build();
    }

    private static final class RecordingProcessedCandleRepository
            implements ProcessedCandleRepository {

        private final Set<String> processed =
                new HashSet<>();

        private int markCalls;

        @Override
        public boolean markAsProcessed(
                String symbol,
                Instant openTime
        ) {

            markCalls++;

            return processed.add(
                    symbol + ":" + openTime
            );
        }
    }
}