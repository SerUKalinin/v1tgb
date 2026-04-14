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

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private final BinanceMarketDataClient marketDataClient;
    private final MarketDataCache marketDataCache;
    private final CandleTransitionDetector detector;           // новый компонент
    private final ApplicationEventPublisher eventPublisher;     // новый компонент

    private final Map<String, AtomicBoolean> readyStatus = new ConcurrentHashMap<>();

    // === Существующие методы (оставлены без изменений) ===

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

    public CandleWindow getWindow(String symbol) {
        return marketDataCache.getWindow(symbol);
    }

    public boolean isReady(String symbol) {
        AtomicBoolean status = readyStatus.get(symbol);
        return status != null && status.get();
    }

    // === Новый метод для событийной модели ===

    /**
     * Единый метод обновления, который вызывается планировщиком.
     * Выполняет fetch, обновление кэша и, если окно готово, пытается
     * сгенерировать событие новой закрытой свечи.
     */
    public void refresh(String symbol, String interval) {
        // 1. Обновляем данные (используем существующую логику)
        updateMarketData(symbol, interval);

        // 2. Проверяем, готово ли окно
        if (!isReady(symbol)) {
            log.debug("[MARKET] Окно для {} ещё не готово", symbol);
            return;
        }

        CandleWindow window = getWindow(symbol);
        if (window == null || window.candles().isEmpty()) {
            return;
        }

        // 3. Пытаемся зарегистрировать новую закрытую свечу через детектор
        detector.detect(window).ifPresent(event -> {
            Candle c = event.closedCandle();
            log.info("[VERIFY] NEW_CANDLE: symbol={} openTime={} closeTime={} O={} H={} L={} C={} V={}",
                    symbol, c.openTime(), c.getCloseTime(), c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), c.getVolume());
            eventPublisher.publishEvent(event);
        });    }
}