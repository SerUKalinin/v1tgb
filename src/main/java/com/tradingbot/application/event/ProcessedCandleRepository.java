package com.tradingbot.application.event;

import java.time.Instant;

/**
 * Репозиторий для отметки обработанных свечей.
 * На Этапе 1 используется in-memory реализация, которая всегда возвращает true.
 */
public interface ProcessedCandleRepository {
    /**
     * Пытается отметить свечу как обработанную.
     * @return true, если свеча не была обработана ранее, иначе false
     */
    boolean markAsProcessed(String symbol, Instant openTime);
}