package com.tradingbot.application.market;

import com.tradingbot.application.event.CandleTransitionDetector;
import com.tradingbot.application.event.NewClosedCandleEvent;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.infrastructure.client.binance.BinanceMarketDataClient;
import com.tradingbot.tracing.IdentityFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сервис управления рыночными данными.
 *
 * <p>Отвечает за:
 * <ul>
 *     <li>загрузку исторических свечей (warm-up)</li>
 *     <li>обновление рыночных данных в реальном времени</li>
 *     <li>поддержание локального кэша свечей</li>
 *     <li>детекцию закрытых свечей</li>
 *     <li>передачу значимых изменений в RiskEngine</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private static final int WARMUP_CANDLE_COUNT = 100;

    private static final int WARMUP_REQUEST_LIMIT =
            WARMUP_CANDLE_COUNT + 1;

    private static final BigDecimal PRICE_FILTER_THRESHOLD =
            new BigDecimal("0.001");

    private final BinanceMarketDataClient marketDataClient;
    private final MarketDataCache marketDataCache;
    private final CandleTransitionDetector detector;
    private final ApplicationEventPublisher eventPublisher;
    private final RiskEngine riskEngine;

    private final Map<String, AtomicBoolean> readyStatus =
            new ConcurrentHashMap<>();

    private final Map<String, BigDecimal> lastRiskPrices =
            new ConcurrentHashMap<>();

    /**
     * Прогревает market-data для основных торговых инструментов.
     */
    public void warmUpAll() {
        warmUp(
                "BTCUSDT",
                "1m"
        );
    }

    /**
     * Загружает исторические свечи и инициализирует кэш.
     *
     * @param symbol   торговый символ
     * @param interval таймфрейм
     */
    public void warmUp(
            String symbol,
            String interval
    ) {
        log.info(
                "[WARMUP] START symbol={} interval={} requested={}",
                symbol,
                interval,
                WARMUP_REQUEST_LIMIT
        );

        AtomicBoolean readiness =
                readyStatus.computeIfAbsent(
                        symbol,
                        key -> new AtomicBoolean(false)
                );

        readiness.set(false);

        try {
            List<Candle> fetched =
                    marketDataClient.getCandles(
                            symbol,
                            interval,
                            WARMUP_REQUEST_LIMIT
                    );

            if (fetched.isEmpty()) {
                throw new IllegalStateException(
                        "Binance не вернул свечи для " + symbol
                );
            }

            long closedCount =
                    fetched.stream()
                            .filter(Candle::isClosed)
                            .count();

            if (closedCount < WARMUP_CANDLE_COUNT) {
                throw new IllegalStateException(
                        "Недостаточно закрытых свечей после warm-up: "
                                + "получено=" + fetched.size()
                                + ", закрытых=" + closedCount
                                + ", требуется=" + WARMUP_CANDLE_COUNT
                );
            }

            List<Candle> closedCandles =
                    fetched.stream()
                            .filter(Candle::isClosed)
                            .toList();

            List<Candle> warmupCandles =
                    closedCandles.subList(
                            closedCandles.size()
                                    - WARMUP_CANDLE_COUNT,
                            closedCandles.size()
                    );

            for (int i = 1;
                 i < warmupCandles.size();
                 i++) {

                Candle previous =
                        warmupCandles.get(i - 1);

                Candle current =
                        warmupCandles.get(i);

                if (!current.getOpenTime()
                        .isAfter(previous.getOpenTime())) {

                    throw new IllegalStateException(
                            "Свечи warm-up расположены "
                                    + "не в хронологическом порядке: "
                                    + "previous="
                                    + previous.getOpenTime()
                                    + ", current="
                                    + current.getOpenTime()
                    );
                }
            }

            marketDataCache.addAll(
                    symbol,
                    warmupCandles
            );

            Candle latestClosed =
                    warmupCandles.get(
                            warmupCandles.size() - 1
                    );

            /*
             * Историческая последняя закрытая свеча
             * является baseline и не должна сразу
             * генерировать новое событие.
             */
            detector.prime(
                    symbol,
                    latestClosed.getOpenTime()
            );

            readiness.set(true);

            log.info(
                    "[WARMUP] SUCCESS symbol={} requested={} received={} closed={} cacheSize={} latestClosedOpen={} latestClosedClose={}",
                    symbol,
                    WARMUP_REQUEST_LIMIT,
                    fetched.size(),
                    closedCount,
                    warmupCandles.size(),
                    latestClosed.getOpenTime(),
                    latestClosed.getCloseTime()
            );

        } catch (Exception e) {

            readiness.set(false);

            log.error(
                    "[WARMUP] FAILED symbol={} interval={}",
                    symbol,
                    interval,
                    e
            );

            throw new IllegalStateException(
                    "Не удалось выполнить warm-up market data для "
                            + symbol,
                    e
            );
        }
    }

    /**
     * Обновляет market-data и RiskEngine price state.
     */
    public void updateMarketData(
            String symbol,
            String interval
    ) {
        try {
            List<Candle> candles =
                    marketDataClient.getCandles(
                            symbol,
                            interval,
                            3
                    );

            if (candles.isEmpty()) {
                return;
            }

            for (Candle candle : candles) {
                marketDataCache.updateOrAdd(
                        symbol,
                        candle
                );
            }

            BigDecimal currentPrice =
                    candles.get(
                            candles.size() - 1
                    ).getClose();

            if (shouldUpdateRisk(
                    symbol,
                    currentPrice
            )) {

                riskEngine.publish(
                        new RiskEvent.PriceUpdated(
                                IdentityFactory.derive(
                                        UUID.nameUUIDFromBytes(
                                                symbol.getBytes()
                                        ),
                                        "price-update"
                                ).toString(),
                                symbol,
                                currentPrice,
                                Instant.now()
                        )
                );

                lastRiskPrices.put(
                        symbol,
                        currentPrice
                );
            }

        } catch (Exception e) {

            log.error(
                    "Ошибка при обновлении данных для {}",
                    symbol,
                    e
            );
        }
    }

    /**
     * Возвращает текущее окно свечей.
     */
    public CandleWindow getWindow(
            String symbol
    ) {
        return marketDataCache.getWindow(symbol);
    }

    /**
     * Проверяет готовность market-data.
     */
    public boolean isReady(
            String symbol
    ) {
        AtomicBoolean status =
                readyStatus.get(symbol);

        return status != null
                && status.get();
    }

    /**
     * Основной цикл обновления market-data.
     *
     * <p>Критическая последовательность:
     *
     * <pre>
     * update
     *   ↓
     * detect
     *   ↓
     * publish event
     *   ↓
     * успешное завершение event chain
     *   ↓
     * markAsProcessed
     * </pre>
     *
     * <p>Если downstream pipeline бросает exception,
     * markAsProcessed() не выполняется.</p>
     */
    public void refresh(
            String symbol,
            String interval
    ) {

        updateMarketData(
                symbol,
                interval
        );

        if (!isReady(symbol)) {
            log.debug(
                    "[MARKET] Окно для {} ещё не готово",
                    symbol
            );

            return;
        }

        CandleWindow window =
                getWindow(symbol);

        if (window == null
                || window.getCandles() == null
                || window.getCandles().isEmpty()) {
            return;
        }

        detector.detect(window)
                .ifPresent(event -> {

                    log.info(
                            "[VERIFY] NEW_CANDLE: symbol={} openTime={} closeTime={} O={} H={} L={} C={} V={}",
                            event.symbol(),
                            event.openTime(),
                            event.closeTime(),
                            event.open(),
                            event.high(),
                            event.low(),
                            event.close(),
                            event.volume()
                    );

                    /*
                     * ApplicationEventPublisher в текущей конфигурации
                     * выполняет обработчики синхронно.
                     *
                     * Поэтому если любой downstream listener
                     * выбросит exception, управление сюда не дойдёт
                     * и candle ACK не будет выполнен.
                     */
                    eventPublisher.publishEvent(
                            event
                    );

                    /*
                     * ACK только после успешного завершения
                     * всей синхронной event chain.
                     */
                    detector.markAsProcessed(
                            event.symbol(),
                            event.openTime()
                    );
                });
    }

    private boolean shouldUpdateRisk(
            String symbol,
            BigDecimal currentPrice
    ) {
        BigDecimal lastPrice =
                lastRiskPrices.get(symbol);

        if (lastPrice == null) {
            return true;
        }

        BigDecimal diff =
                currentPrice
                        .subtract(lastPrice)
                        .abs();

        BigDecimal threshold =
                lastPrice.multiply(
                        PRICE_FILTER_THRESHOLD
                );

        return diff.compareTo(threshold) >= 0;
    }
}