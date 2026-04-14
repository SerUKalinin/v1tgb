package com.tradingbot.application;

import com.tradingbot.application.pipeline.TradingPipelineService;
import com.tradingbot.application.service.PositionService;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.model.MarketData;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.strategy.TradingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("backtest")
class TradingPipelineIntegrationTest {

    @Autowired
    private TradingPipelineService tradingPipeline;

    @Autowired
    private PositionService positionService;

    @MockBean
    private TradingStrategy tradingStrategy;

    @Test
    void shouldExecuteTradeAndSavePositionWhenBuySignalReceived() {
        // GIVEN: Рынок дает цену, а стратегия говорит КУПИТЬ
        String symbol = "BTCUSDT";
        BigDecimal price = new BigDecimal("60000");
        MarketData marketData = new MarketData(symbol, price, Instant.now());
        
        when(tradingStrategy.generateSignal(any())).thenReturn(
                new Signal(symbol, SignalType.BUY, price)
        );

        // WHEN: Прогоняем данные через Pipeline
        tradingPipeline.process(marketData);

        // THEN: Проверяем, что позиция открылась и сохранена
        assertTrue(positionService.hasOpenPosition(symbol), "Позиция должна быть открыта");
        
        Position position = positionService.calculatePnL(symbol, price).equals(BigDecimal.ZERO) ? 
                new Position(symbol, BigDecimal.ZERO, BigDecimal.ZERO) : null; // упрощенно для теста
        
        // Проверяем через сервис позиций
        // Так как у нас DefaultRiskManager дает 0.01 лота
        // Мы можем проверить наличие позиции в памяти сервиса
        assertTrue(positionService.hasOpenPosition(symbol));
    }
}
