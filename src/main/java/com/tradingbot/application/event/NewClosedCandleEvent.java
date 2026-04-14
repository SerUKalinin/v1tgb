package com.tradingbot.application.event;

import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;

public record NewClosedCandleEvent(
        String symbol,
        Candle closedCandle,
        CandleWindow windowSnapshot
) {}