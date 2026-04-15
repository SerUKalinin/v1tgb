package com.tradingbot.application.service;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.model.Trade;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("backtest")
@Transactional
public class LedgerReliabilityIntegrationTest {

    @Autowired
    private TradeService tradeService;

    @Autowired
    private PositionRebuildService rebuildService;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        tradeRepository.deleteAll();
        positionRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    void shouldRebuildPositionFromTradeHistory() {
        // 1. Given: Серия сделок через события
        publishTrade("T1", "BTCUSDT", OrderSide.BUY, "0.5", "50000");
        publishTrade("T2", "BTCUSDT", OrderSide.BUY, "0.5", "60000");
        publishTrade("T3", "BTCUSDT", OrderSide.SELL, "0.2", "70000");

        // 2. When: Выполняем Rebuild
        rebuildService.rebuildAllPositions();

        // 3. Then: Проверяем кэш позиций
        Optional<PositionEntity> posOpt = positionRepository.findById("BTCUSDT");
        assertThat(posOpt).isPresent();
        PositionEntity pos = posOpt.get();

        assertThat(pos.getQuantity()).isEqualByComparingTo("0.8");
        assertThat(pos.getEntryPrice()).isEqualByComparingTo("55000");
    }

    @Test
    void shouldIgnoreDuplicateTradesByExchangeId() {
        // 1. Given: Сделка уже записана через событие
        publishTrade("DUP_1", "ETHUSDT", OrderSide.BUY, "1.0", "2000");
        assertThat(tradeRepository.count()).isEqualTo(1);

        // 2. When: Пытаемся отправить то же событие еще раз
        publishTrade("DUP_1", "ETHUSDT", OrderSide.BUY, "1.0", "2000");

        // 3. Then: Количество сделок не должно измениться
        assertThat(tradeRepository.count()).isEqualTo(1);
    }

    private void publishTrade(String tradeId, String symbol, OrderSide side, String qty, String price) {
        String orderId = "O-" + tradeId;
        String clientOrderId = "C-" + tradeId;
        orderRepository.save(com.tradingbot.infrastructure.persistence.entity.OrderEntity.builder()
                .id(orderId)
                .clientOrderId(clientOrderId)
                .symbol(symbol)
                .side(side)
                .strategyId("default")
                .quantity(new BigDecimal(qty))
                .price(new BigDecimal(price))
                .status("FILLED")
                .build());

        eventPublisher.publishEvent(new OrderFilledEvent(orderId, "EXT-" + tradeId, symbol, new BigDecimal(qty), new BigDecimal(price)));
    }
}
