package com.tradingbot.application.event;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реализация репозитория обработанных свечей с хранением в памяти.
 *
 * Используется как in-memory механизм идемпотентности для предотвращения
 * повторной обработки одной и той же свечи в рамках одного запуска приложения.
 *
 * Потокобезопасность обеспечивается за счёт {@link ConcurrentHashMap}.
 */
@Component
public class InMemoryProcessedCandleRepository implements ProcessedCandleRepository {

    /**
     * Хранилище обработанных свечей.
     *
     * Key: composite key (symbol:openTime)
     * Value: время открытия свечи (openTime)
     */
    private final Map<String, Instant> storage = new ConcurrentHashMap<>();

    /**
     * Отмечает свечу как обработанную.
     *
     * Если запись уже существует, метод вернёт false.
     * Если свеча новая — она будет зафиксирована и метод вернёт true.
     *
     * @param symbol торговый символ
     * @param openTime время открытия свечи
     * @return true если свеча обработана впервые, иначе false
     */
    @Override
    public boolean markAsProcessed(String symbol, Instant openTime) {
        String key = symbol + ":" + openTime;
        Instant existing = storage.putIfAbsent(key, openTime);
        return existing == null;
    }
}