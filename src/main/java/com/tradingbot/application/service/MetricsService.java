package com.tradingbot.application.service;

import com.tradingbot.domain.model.Trade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MetricsService {
    // здесь equity curve, win/loss, profit factor и т.д.
    public void recordTrade(Trade trade, boolean isWin) {
        log.info("METRICS: trade recorded");
    }
}