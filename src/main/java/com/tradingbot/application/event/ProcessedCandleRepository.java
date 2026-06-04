package com.tradingbot.application.event;

import java.time.Instant;

/**
 * Репозиторий для отметки обработанных свечей.
 */
public interface ProcessedCandleRepository {


    /**
     * Отмечает свечу как обработанную.
     *
     * @param symbol   торговый символ
     * @param openTime время открытия свечи
     * @return true, если свеча не была обработана ранее, иначе false
     */
    boolean markAsProcessed(String symbol, Instant openTime);
}