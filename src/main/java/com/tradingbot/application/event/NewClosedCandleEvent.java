package com.tradingbot.application.event;

import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;

/**
 * Событие, возникающее при обнаружении новой закрытой свечи.
 *
 * @param symbol         торговый символ
 * @param closedCandle   закрывшаяся свеча
 * @param windowSnapshot снапшот окна свечей на момент закрытия
 */
public record NewClosedCandleEvent(
        String symbol,
        Candle closedCandle,
        CandleWindow windowSnapshot
) {}