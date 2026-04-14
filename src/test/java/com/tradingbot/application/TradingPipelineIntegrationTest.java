package com.tradingbot.application;

import com.tradingbot.application.pipeline.TradingPipeline;
import com.tradingbot.application.service.PositionService;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class TradingPipelineIntegrationTest {

    @Autowired
    private TradingPipeline tradingPipeline;

    @Autowired
    private PositionService positionService;

    @MockBean
    private TradingStrategy tradingStrategy;

    @Test
    void shouldExecuteTradeAndSavePositionWhenBuySignalReceived() {
        // GIVEN: Рынок дает свечу, а стратегия говорит КУПИТЬ
        String symbol = "BTCUSDT";
        BigDecimal price = new BigDecimal("60000");
        Instant now = Instant.now();
        Candle candle = Candle.builder()
                .openTime(now)
                .open(price)
                .high(price)
                .low(price)
                .close(price)
                .volume(BigDecimal.TEN)
                .closeTime(now.plusSeconds(60))
                .build();
        CandleWindow window = new CandleWindow(symbol, List.of(candle));
        
        when(tradingStrategy.analyze(any())).thenReturn(
                new Signal(symbol, SignalType.BUY, price)
        );

        // WHEN: Прогоняем данные через Pipeline
        tradingPipeline.process(window);

        // THEN: Проверяем, что позиция открылась и данные верны
        assertTrue(positionService.hasOpenPosition(symbol), "Позиция должна быть открыта");
        
        Position position = positionService.getPosition(symbol);
        assertEquals(symbol, position.getSymbol());
        assertEquals(0, position.getEntryPrice().compareTo(price), "Цена входа должна совпадать");
    }
}
