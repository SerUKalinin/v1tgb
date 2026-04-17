package com.tradingbot.application.pipeline;

import com.tradingbot.application.market.MarketDataService;
import com.tradingbot.application.event.NewClosedCandleEvent;
import com.tradingbot.domain.model.CandleWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import org.springframework.scheduling.annotation.Async;

/**
 * Сервис-связка между событиями рынка и торговым конвейером.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CandleEventListener {

    private final TradingPipeline pipeline;
    private final MarketDataService marketDataService;

    /**
     * Слушает события о новых закрытых свечах и запускает конвейер асинхронно.
     *
     * @param event событие новой свечи
     */
    @Async
    @EventListener
    public void onNewCandle(NewClosedCandleEvent event) {        String symbol = event.symbol();
        log.info("[PIPELINE-SERVICE] Received new candle event for {}", symbol);

        CandleWindow window = marketDataService.getWindow(symbol);
        if (window != null) {
            pipeline.process(window);
        } else {
            log.warn("[PIPELINE-SERVICE] Could not get window for {}", symbol);
        }
    }
}
