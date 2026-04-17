package com.tradingbot.application.market;

import com.tradingbot.application.event.CandleTransitionDetector;
import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.domain.risk.RiskEngine;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.infrastructure.client.binance.BinanceMarketDataClient;
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
     * Выполняет прогрев данных: загружает 100 свечей и устанавливает флаг готовности.
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
     * Обновляет рыночные данные и уведомляет RiskEngine при значимых изменениях.
     */
    public void updateMarketData(String symbol, String interval) {
        try {
            List<Candle> candles = marketDataClient.getCandles(symbol, interval, 3);
            if (candles.isEmpty()) return;

            for (Candle candle : candles) {
                marketDataCache.updateOrAdd(symbol, candle);
            }

            // Фильтрация и отправка в RiskEngine
            BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();
            if (shouldUpdateRisk(symbol, currentPrice)) {
                riskEngine.publish(new RiskEvent.PriceUpdated(
                        UUID.randomUUID().toString(),
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

    public CandleWindow getWindow(String symbol) {
        return marketDataCache.getWindow(symbol);
    }

    public boolean isReady(String symbol) {
        AtomicBoolean status = readyStatus.get(symbol);
        return status != null && status.get();
    }

    public void refresh(String symbol, String interval) {
        updateMarketData(symbol, interval);

        if (!isReady(symbol)) {
            log.debug("[MARKET] Окно для {} ещё не готово", symbol);
            return;
        }

        CandleWindow window = getWindow(symbol);
        if (window == null || window.candles().isEmpty()) {
            return;
        }

        detector.detect(window).ifPresent(event -> {
            log.info("[VERIFY] NEW_CANDLE: symbol={} closeTime={} O={} H={} L={} C={} V={}",
                    symbol, event.closeTime(), event.open(), event.high(), event.low(), event.close(), event.volume());
            eventPublisher.publishEvent(event);
        });
    }

    private boolean shouldUpdateRisk(String symbol, BigDecimal currentPrice) {
        BigDecimal lastPrice = lastRiskPrices.get(symbol);
        if (lastPrice == null) return true;

        BigDecimal diff = currentPrice.subtract(lastPrice).abs();
        BigDecimal threshold = lastPrice.multiply(PRICE_FILTER_THRESHOLD);
        
        return diff.compareTo(threshold) >= 0;
    }
}