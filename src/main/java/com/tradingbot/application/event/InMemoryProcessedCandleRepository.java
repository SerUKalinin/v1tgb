package com.tradingbot.application.event;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryProcessedCandleRepository implements ProcessedCandleRepository {

    private final Map<String, Instant> storage = new ConcurrentHashMap<>();

    @Override
    public boolean markAsProcessed(String symbol, Instant openTime) {
        String key = symbol + ":" + openTime;
        Instant existing = storage.putIfAbsent(key, openTime);
        return existing == null;
    }
}