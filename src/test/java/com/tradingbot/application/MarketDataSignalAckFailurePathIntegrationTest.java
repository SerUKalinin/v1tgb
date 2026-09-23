package com.tradingbot.application;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.market.MarketDataService;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.strategy.StrategyEngine;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.infrastructure.client.binance.BinanceMarketDataClient;
import com.tradingbot.infrastructure.persistence.repository.SignalClaimRepository;
import com.tradingbot.application.service.order.OrderApplicationService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        properties = {
                "app.outbox.enabled=true",
                "spring.task.scheduling.enabled=false"
        }
)
@ActiveProfiles("test")
class MarketDataSignalAckFailurePathIntegrationTest
        extends BaseIntegrationTest {

    /**
     * В test context существует @Primary FakeMarketDataService.
     *
     * Здесь сознательно выбираем настоящий production bean
     * по имени, чтобы протестировать реальный MarketDataService.
     */
    @Resource(name = "marketDataService")
    private MarketDataService marketDataService;

    @Autowired
    private SignalClaimRepository signalClaimRepository;

    @MockBean
    private BinanceMarketDataClient marketDataClient;

    @MockBean
    private RiskEngine riskEngine;

    @MockBean
    private StrategyEngine strategyEngine;

    @MockBean
    private OrderApplicationService orderApplicationService;

    @MockBean
    private SystemStateManager systemStateManager;

    @MockBean
    private ExecutionPort executionPort;

    private UUID signalId;

    private Instant newCandleOpenTime;

    private SignalEvent signal;

    @BeforeEach
    void prepareTestEnvironment() {

        signalClaimRepository.deleteAll();

        when(systemStateManager.isReady())
                .thenReturn(true);

        when(systemStateManager.isTradingEnabled())
                .thenReturn(true);

        /*
         * 100 закрытых свечей для warm-up.
         *
         * Последняя из них будет baseline detector.
         */
        Instant baselineOpenTime =
                Instant.parse(
                        "2026-09-23T13:00:00Z"
                );

        List<Candle> warmupCandles =
                createWarmupCandles(
                        baselineOpenTime,
                        100
                );

        /*
         * Следующая свеча новее baseline.
         */
        newCandleOpenTime =
                baselineOpenTime.plusSeconds(
                        60L * 100
                );

        Candle newClosedCandle =
                Candle.of(
                        "BTCUSDT",
                        new BigDecimal("100.00"),
                        new BigDecimal("102.00"),
                        new BigDecimal("99.00"),
                        new BigDecimal("101.00"),
                        new BigDecimal("5.00"),
                        newCandleOpenTime,
                        newCandleOpenTime.plusSeconds(59),
                        true
                );

        /*
         * Warm-up должен получить достаточно закрытых свечей.
         */
        when(
                marketDataClient.getCandles(
                        "BTCUSDT",
                        "1m",
                        101
                )
        ).thenReturn(
                warmupCandles
        );

        /*
         * Каждое обновление рынка получает новую закрытую свечу.
         *
         * Важно: если candle НЕ ACK-нута после первого failure,
         * второй refresh должен снова увидеть её.
         */
        when(
                marketDataClient.getCandles(
                        "BTCUSDT",
                        "1m",
                        3
                )
        ).thenReturn(
                List.of(
                        newClosedCandle
                )
        );

        /*
         * Фиксированный signalId нужен для проверки rollback claim.
         */
        signalId =
                UUID.randomUUID();

        signal =
                new SignalEvent(
                        signalId,
                        "BTCUSDT",
                        SignalType.BUY,
                        new BigDecimal("101.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        newClosedCandle.getCloseTime(),
                        "FAILURE_PATH_TEST"
                );

        when(
                strategyEngine.evaluate(
                        any()
                )
        ).thenReturn(
                Optional.of(signal)
        );

        /*
         * Намеренно ломаем downstream после SignalClaim.
         *
         * Ожидаем:
         *
         * claim
         *   ↓
         * exception
         *   ↓
         * rollback transaction
         */
        when(
                orderApplicationService.handleSignal(
                        any()
                )
        ).thenThrow(
                new IllegalStateException(
                        "FORCED_DOWNSTREAM_FAILURE"
                )
        );
    }

    @Test
    void shouldRollbackSignalClaimAndNotAckCandleWhenSignalPipelineFails() {

        /*
         * Warm-up устанавливает baseline detector.
         */
        marketDataService.warmUp(
                "BTCUSDT",
                "1m"
        );

        /*
         * Первый refresh:
         *
         * Market Data
         *      ↓
         * Closed Candle
         *      ↓
         * Strategy
         *      ↓
         * Signal
         *      ↓
         * SignalClaim
         *      ↓
         * downstream failure
         *
         * Если SignalEventListener правильно
         * пробрасывает exception, refresh() должен завершиться
         * исключением.
         */
        assertThrows(
                IllegalStateException.class,
                () ->
                        marketDataService.refresh(
                                "BTCUSDT",
                                "1m"
                        ),
                "Downstream failure must propagate outside SignalEventListener"
        );

        /*
         * Ключевая транзакционная проверка.
         *
         * SignalClaim был создан перед exception,
         * но обязан откатиться вместе с транзакцией.
         */
        assertFalse(
                signalClaimRepository.existsById(
                        signalId
                ),
                "SignalClaim must be rolled back after downstream failure"
        );

        /*
         * Проверяем, что новая свеча действительно присутствует
         * в market-data window.
         */
        CandleWindow window =
                marketDataService.getWindow(
                        "BTCUSDT"
                );

        assertTrue(
                window != null,
                "Market data window must exist"
        );

        assertTrue(
                window.getCandles() != null,
                "Market data candles must exist"
        );

        assertTrue(
                window.getCandles()
                        .stream()
                        .anyMatch(
                                candle ->
                                        candle.getOpenTime()
                                                .equals(
                                                        newCandleOpenTime
                                                )
                        ),
                "New closed candle must remain in market-data cache"
        );

        /*
         * Второй refresh должен снова обработать ту же свечу.
         *
         * Если первый refresh сделал ACK несмотря на failure,
         * StrategyEngine будет вызван только один раз.
         *
         * Если ACK НЕ был сделан:
         *
         * first refresh  -> evaluate()
         * second refresh -> evaluate()
         */
        assertThrows(
                IllegalStateException.class,
                () ->
                        marketDataService.refresh(
                                "BTCUSDT",
                                "1m"
                        ),
                "The same candle must remain retryable after failed processing"
        );

        verify(
                strategyEngine,
                times(2)
        ).evaluate(
                any()
        );

        /*
         * После второй неудачной попытки claim также
         * обязан отсутствовать.
         */
        assertFalse(
                signalClaimRepository.existsById(
                        signalId
                ),
                "SignalClaim must remain absent after repeated failures"
        );
    }

    private List<Candle> createWarmupCandles(
            Instant firstOpenTime,
            int count
    ) {

        List<Candle> candles =
                new ArrayList<>();

        for (int i = 0; i < count; i++) {

            Instant openTime =
                    firstOpenTime.plusSeconds(
                            60L * i
                    );

            candles.add(
                    Candle.of(
                            "BTCUSDT",
                            new BigDecimal("100.00"),
                            new BigDecimal("101.00"),
                            new BigDecimal("99.00"),
                            new BigDecimal("100.50"),
                            new BigDecimal("5.00"),
                            openTime,
                            openTime.plusSeconds(59),
                            true
                    )
            );
        }

        return candles;
    }
}