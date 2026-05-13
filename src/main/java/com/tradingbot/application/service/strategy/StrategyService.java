package com.tradingbot.application.service.strategy;

import com.tradingbot.application.event.NewClosedCandleEvent;
import com.tradingbot.application.market.MarketDataService;
import com.tradingbot.application.service.order.OrderApplicationService;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.tracing.IdentityFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис для управления торговыми стратегиями.
 * Слушает события закрытых свечей и генерирует торговые сигналы.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyService {

    private static final Duration TEST_SIGNAL_COOLDOWN = Duration.ofMinutes(15);
    private final Map<String, Instant> lastSignalTimes = new ConcurrentHashMap<>();

    private final MarketDataService marketDataService;
    private final OrderApplicationService orderApplicationService;
    private final SystemStateManager stateManager;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void onNewCandle(NewClosedCandleEvent event) {
        if (!stateManager.isReady()) {
            return;
        }

        log.info("[STRATEGY] Processing new candle for symbol: {}", event.symbol());
        
        // Временная тестовая логика: генерируем BUY сигнал на каждую закрытую свечу
        generateTestSignal(event);
    }

    public void onMarketUpdate(String symbol, BigDecimal price) {
        if (!stateManager.isReady()) {
            return;
        }
    }

    private void generateTestSignal(NewClosedCandleEvent candle) {
        Instant now = Instant.now();
        Instant lastSignal = lastSignalTimes.get(candle.symbol());

        if (lastSignal != null && Duration.between(lastSignal, now).compareTo(TEST_SIGNAL_COOLDOWN) < 0) {
            log.debug("[STRATEGY] Throttling test signal for {}. Last signal was at {}", candle.symbol(), lastSignal);
            return;
        }

        lastSignalTimes.put(candle.symbol(), now);

        UUID signalId = IdentityFactory.derive(UUID.nameUUIDFromBytes(candle.symbol().getBytes()), "test-signal-" + candle.closeTime());
        SignalEvent signal = new SignalEvent(
                signalId,
                candle.symbol(),
                SignalType.BUY,
                candle.close(),
                BigDecimal.ZERO, // quantity
                BigDecimal.ZERO, // stopLoss
                BigDecimal.ZERO, // takeProfit
                candle.closeTime(),
                "SMA_CROSS_STUB"
        );

        log.info("[STRATEGY] Generated test signal: {} {} at {}", 
                signal.getSymbol(), signal.getType(), signal.getPrice());        
        eventPublisher.publishEvent(signal);
    }
}
