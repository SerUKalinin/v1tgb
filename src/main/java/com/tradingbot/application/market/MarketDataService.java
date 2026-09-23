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

    /**
     * Количество закрытых свечей, необходимое для warm-up.
     */
    private static final int WARMUP_CANDLE_COUNT = 100;

    /**
     * Binance может вернуть текущую формирующуюся свечу.
     *
     * Поэтому запрашиваем одну свечу сверх необходимого количества,
     * после чего оставляем только реально закрытые.
     */
    private static final int WARMUP_REQUEST_LIMIT =
            WARMUP_CANDLE_COUNT + 1;

    /**
     * Порог изменения цены для публикации PriceUpdated.
     * 0.1%.
     */
    private static final BigDecimal PRICE_FILTER_THRESHOLD =
            new BigDecimal("0.001");

    private final BinanceMarketDataClient marketDataClient;
    private final MarketDataCache marketDataCache;
    private final CandleTransitionDetector detector;
    private final ApplicationEventPublisher eventPublisher;
    private final RiskEngine riskEngine;

    /**
     * Признак готовности market data по символу.
     */
    private final Map<String, AtomicBoolean> readyStatus =
            new ConcurrentHashMap<>();

    /**
     * Последняя цена, отправленная в RiskEngine.
     */
    private final Map<String, BigDecimal> lastRiskPrices =
            new ConcurrentHashMap<>();

    /**
     * Прогревает данные для всех основных торговых инструментов.
     */
    public void warmUpAll() {
        warmUp("BTCUSDT", "1m");
    }

    /**
     * Загружает исторические свечи и инициализирует кэш.
     *
     * <p>На Binance последняя возвращённая свеча
     * может быть ещё формирующейся.
     *
     * Поэтому для получения 100 закрытых свечей
     * запрашивается 101 candle, после чего все незакрытые
     * свечи отбрасываются.
     *
     * @param symbol   торговый символ
     * @param interval таймфрейм свечей
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

        AtomicBoolean readiness = readyStatus.computeIfAbsent(
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

            long closedCount = fetched.stream()
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

            List<Candle> closedCandles = fetched.stream()
                    .filter(Candle::isClosed)
                    .toList();

            List<Candle> warmupCandles =
                    closedCandles.subList(
                            closedCandles.size()
                                    - WARMUP_CANDLE_COUNT,
                            closedCandles.size()
                    );

            /*
             * Дополнительная защита от некорректного порядка.
             */
            for (int i = 1; i < warmupCandles.size(); i++) {
                Candle previous = warmupCandles.get(i - 1);
                Candle current = warmupCandles.get(i);

                if (!current.getOpenTime()
                        .isAfter(previous.getOpenTime())) {

                    throw new IllegalStateException(
                            "Свечи warm-up расположены "
                                    + "не в хронологическом порядке: "
                                    + "previous=" + previous.getOpenTime()
                                    + ", current=" + current.getOpenTime()
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

            /*
             * Warm-up является обязательной стадией bootstrap.
             *
             * Не проглатываем ошибку, иначе bootstrap может
             * ошибочно перевести систему в READY/TRADING_ENABLED.
             */
            throw new IllegalStateException(
                    "Не удалось выполнить warm-up market data для "
                            + symbol,
                    e
            );
        }
    }

    /**
     * Обновляет рыночные данные и при необходимости
     * публикует события в RiskEngine.
     *
     * @param symbol   торговый символ
     * @param interval таймфрейм
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

            /*
             * Последняя свеча является наиболее актуальной
             * рыночной ценой, независимо от того,
             * закрыта она или ещё формируется.
             */
            BigDecimal currentPrice =
                    candles.get(candles.size() - 1)
                            .getClose();

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
     * Возвращает текущее окно свечей из кэша.
     *
     * @param symbol торговый символ
     * @return окно свечей
     */
    public CandleWindow getWindow(
            String symbol
    ) {
        return marketDataCache.getWindow(symbol);
    }

    /**
     * Проверяет, завершён ли warm-up для символа.
     *
     * @param symbol торговый символ
     * @return true если данные готовы
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
     * Основной цикл обновления рынка.
     *
     * <p>Последовательность:
     * <ol>
     *     <li>обновление свечей</li>
     *     <li>проверка готовности</li>
     *     <li>получение окна</li>
     *     <li>детекция последней закрытой свечи</li>
     *     <li>публикация NewClosedCandleEvent</li>
     * </ol>
     *
     * @param symbol   торговый символ
     * @param interval таймфрейм
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
                            "[VERIFY] NEW_CANDLE: symbol={} closeTime={} O={} H={} L={} C={} V={}",
                            event.symbol(),
                            event.closeTime(),
                            event.open(),
                            event.high(),
                            event.low(),
                            event.close(),
                            event.volume()
                    );

                    eventPublisher.publishEvent(
                            event
                    );
                });
    }

    /**
     * Проверяет, нужно ли отправлять обновление
     * в RiskEngine на основе порога изменения цены.
     *
     * @param symbol       торговый символ
     * @param currentPrice текущая цена
     * @return true если изменение достаточно велико
     */
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