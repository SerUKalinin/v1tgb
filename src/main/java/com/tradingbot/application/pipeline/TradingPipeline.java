package com.tradingbot.application.pipeline;

import com.tradingbot.domain.market.MarketSnapshot;
import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.position.PortfolioState;
import com.tradingbot.domain.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Trading Pipeline — routes CandleWindow through all registered strategies.
 *
 * Responsibilities:
 * - Build MarketSnapshot and PortfolioState from available data
 * - Fan out to all TradingStrategy implementations
 * - Publish resulting signals as application events
 *
 * Does NOT: call Risk Engine, OMS, or Execution layer directly.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradingPipeline {

    private final List<TradingStrategy> strategies;
    private final ApplicationEventPublisher eventPublisher;
    private final com.tradingbot.application.service.PositionService positionService;

    /**
     * Process a CandleWindow: build snapshot, run strategies, emit signals.
     *
     * @param window ready candle window for a symbol
     */
    public void process(CandleWindow window) {
        if (window == null || !window.isReady()) {
            log.debug("[PIPELINE] Window for {} is null or not ready", window == null ? "?" : window.getSymbol());
            return;
        }

        String symbol = window.getSymbol();
        log.info("[PIPELINE] Processing symbol={} candles={}", symbol, window.size());

        MarketSnapshot snapshot = buildSnapshot(window);

        PortfolioState portfolio = positionService.getPortfolioState();

        for (TradingStrategy strategy : strategies) {            try {
                strategy.decide(snapshot, portfolio).ifPresent(signal -> {
                    log.info("[PIPELINE] Strategy {} emitted {} for {}",
                            strategy.strategyId(), signal.getSide(), symbol);
                    eventPublisher.publishEvent(signal);
                });
            } catch (Exception e) {
                // Strategy errors must never crash the pipeline
                log.error("[PIPELINE] Strategy {} threw an exception for {}",
                        strategy.strategyId(), symbol, e);
            }
        }
    }

    private MarketSnapshot buildSnapshot(CandleWindow window) {
        return new MarketSnapshot(
                window.getSymbol(),
                window.getLast().getClose(),
                window.getCandles(),
                Instant.now()
        );
    }
}