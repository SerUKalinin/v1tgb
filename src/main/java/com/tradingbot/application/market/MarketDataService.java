package com.tradingbot.application.market;

import com.tradingbot.application.event.CandleTransitionDetector;
import com.tradingbot.application.event.NewClosedCandleEvent;
import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.application.risk.RiskEngine;
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
 * <p>
 * Отвечает за:
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

    private final BinanceMarketDataClient marketDataClient;
    private final MarketDataCache marketDataCache;
    private final CandleTransitionDetector detector;
    private final ApplicationEventPublisher eventPublisher;
    private final RiskEngine riskEngine;

    private final Map<String, AtomicBoolean> readyStatus = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> lastRiskPrices = new ConcurrentHashMap<>();

    private static final BigDecimal PRICE_FILTER_THRESHOLD = new BigDecimal("0.001"); // 0.1%

    /**
     * Прогревает данные для всех основных торговых инструментов.
     * <p>
     * Используется на этапе bootstrap системы.
     */
    public void warmUpAll() {
        warmUp("BTCUSDT", "1m");
    }

    /**
     * Загружает исторические свечи и инициализирует кэш.
     *
     * @param symbol торговый символ
     * @param interval таймфрейм свечей
     */
    public void warmUp(String symbol, String interval) {
        log.info("Запуск прогрева данных для {}: interval={}", symbol, interval);
        try {
            List<Candle> candles = marketDataClient.getCandles(symbol, interval, 100);
            marketDataCache.addAll(symbol, candles);
            readyStatus.computeIfAbsent(symbol, k -> new AtomicBoolean(true)).set(true);
            log.info("Прогрев {} завершен. Загружено {} свечей.", symbol, candles.size());
        } catch (Exception e) {
            log.error("Ошибка при прогреве данных для {}", symbol, e);
        }
    }

    /**
     * Обновляет рыночные данные и при необходимости публикует события в RiskEngine.
     * <p>
     * Используется в потоковой обработке market data.
     */
    public void updateMarketData(String symbol, String interval) {
        try {
            List<Candle> candles = marketDataClient.getCandles(symbol, interval, 3);
            if (candles.isEmpty()) return;

            for (Candle candle : candles) {
                marketDataCache.updateOrAdd(symbol, candle);
            }

            BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();

            if (shouldUpdateRisk(symbol, currentPrice)) {
                riskEngine.publish(new RiskEvent.PriceUpdated(
                        IdentityFactory.derive(
                                UUID.nameUUIDFromBytes(symbol.getBytes()),
                                "price-update"
                        ).toString(),
                        symbol,
                        currentPrice,
                        Instant.now()
                ));

                lastRiskPrices.put(symbol, currentPrice);
            }

        } catch (Exception e) {
            log.error("Ошибка при обновлении данных для {}", symbol, e);
        }
    }

    /**
     * Возвращает текущее окно свечей из кэша.
     *
     * @param symbol торговый символ
     * @return окно свечей
     */
    public CandleWindow getWindow(String symbol) {
        return marketDataCache.getWindow(symbol);
    }

    /**
     * Проверяет, завершён ли warm-up для символа.
     *
     * @param symbol торговый символ
     * @return true если данные готовы
     */
    public boolean isReady(String symbol) {
        AtomicBoolean status = readyStatus.get(symbol);
        return status != null && status.get();
    }

    /**
     * Основной цикл обновления рынка:
     * <ul>
     *     <li>обновляет свечи</li>
     *     <li>проверяет готовность</li>
     *     <li>детектирует закрытие свечи</li>
     *     <li>публикует событие NewClosedCandleEvent</li>
     * </ul>
     *
     * @param symbol торговый символ
     * @param interval таймфрейм
     */
    public void refresh(String symbol, String interval) {
        updateMarketData(symbol, interval);

        if (!isReady(symbol)) {
            log.debug("[MARKET] Окно для {} ещё не готово", symbol);
            return;
        }

        CandleWindow window = getWindow(symbol);
        if (window == null || window.getCandles().isEmpty()) {
            return;
        }

        detector.detect(window).ifPresent(event -> {
            log.info("[VERIFY] NEW_CANDLE: symbol={} closeTime={} O={} H={} L={} C={} V={}",
                    symbol, event.closeTime(), event.open(), event.high(), event.low(), event.close(), event.volume());

            eventPublisher.publishEvent(event);
        });
    }

    /**
     * Проверяет, нужно ли отправлять обновление в RiskEngine
     * на основе порога изменения цены.
     */
    private boolean shouldUpdateRisk(String symbol, BigDecimal currentPrice) {
        BigDecimal lastPrice = lastRiskPrices.get(symbol);
        if (lastPrice == null) return true;

        BigDecimal diff = currentPrice.subtract(lastPrice).abs();
        BigDecimal threshold = lastPrice.multiply(PRICE_FILTER_THRESHOLD);

        return diff.compareTo(threshold) >= 0;
    }
}