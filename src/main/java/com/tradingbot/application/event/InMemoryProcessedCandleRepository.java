package com.tradingbot.application.event;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реализация репозитория обработанных свечей с хранением в памяти.
 * <p>
 * Использует {@link ConcurrentHashMap} для потокобезопасности.
 */
@Component
public class InMemoryProcessedCandleRepository implements ProcessedCandleRepository {

    private final Map<String, Instant>   storage = new ConcurrentHashMap<>();


    /**
     * Отмечает свечу как обработанную.
     *
     * @param symbol   торговый символ
     * @param openTime время открытия свечи
     * @return true, если свеча не была обработана ранее, false в противном случае
     */
    @Override
    public boolean markAsProcessed(String symbol, Instant openTime) {
        String key = symbol + ":" + openTime;
        Instant existing = storage.putIfAbsent(key, openTime);
        return existing == null;
    }
}