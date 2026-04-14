package com.tradingbot.application;

import com.tradingbot.application.event.CandleTransitionDetector;
import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.infrastructure.client.binance.BinanceMarketDataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сервис управления рыночными данными.
 * <p>
 * Обеспечивает загрузку, кэширование и обновление свечных данных.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private final BinanceMarketDataClient marketDataClient;
    private final MarketDataCache marketDataCache;
    private final CandleTransitionDetector detector;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, AtomicBoolean> readyStatus = new ConcurrentHashMap<>();

    /**
     * Выполняет прогрев данных: загружает 100 свечей и устанавливает флаг готовности.
     *
     * @param symbol   торговый символ
     * @param interval свечной интервал
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
     * Обновляет рыночные данные: загружает последние 3 свечи.
     *
     * @param symbol   торговый символ
     * @param interval свечной интервал
     */
    public void updateMarketData(String symbol, String interval) {
        try {
            List<Candle> candles = marketDataClient.getCandles(symbol, interval, 3);
            for (Candle candle : candles) {
                marketDataCache.updateOrAdd(symbol, candle);
            }
        } catch (Exception e) {
            log.error("Ошибка при обновлении данных для {}", symbol, e);
        }
    }

    /**
     * Возвращает окно свечей для указанного символа.
     *
     * @param symbol торговый символ
     * @return окно свечей
     */
    public CandleWindow getWindow(String symbol) {
        return marketDataCache.getWindow(symbol);
    }

    /**
     * Проверяет, готово ли окно для указанного символа.
     *
     * @param symbol торговый символ
     * @return true, если окно готово
     */
    public boolean isReady(String symbol) {
        AtomicBoolean status = readyStatus.get(symbol);
        return status != null && status.get();
    }

    /**
     * Единый метод обновления, вызываемый планировщиком.
     * Выполняет обновление данных и генерацию события новой закрытой свечи.
     *
     * @param symbol   торговый символ
     * @param interval свечной интервал
     */
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
            Candle c = event.closedCandle();
            log.info("[VERIFY] NEW_CANDLE: symbol={} openTime={} closeTime={} O={} H={} L={} C={} V={}",
                    symbol, c.openTime(), c.getCloseTime(), c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), c.getVolume());
            eventPublisher.publishEvent(event);
        });
    }
}