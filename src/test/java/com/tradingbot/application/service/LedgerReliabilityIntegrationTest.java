package com.tradingbot.application.service;

import com.tradingbot.application.service.execution.PositionRebuildService;
import com.tradingbot.application.service.execution.TradeService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
public class LedgerReliabilityIntegrationTest extends com.tradingbot.BaseIntegrationTest {

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

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @BeforeEach
    void setUp() {
        tradeRepository.deleteAll();
        positionRepository.deleteAll();
        orderRepository.deleteAll();
        entityManager.flush();
        entityManager.clear();
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
        Optional<PositionEntity> posOpt = positionRepository.findBySymbol("BTCUSDT");
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
        UUID orderId = UUID.nameUUIDFromBytes(tradeId.getBytes()); // Deterministic UUID for tests
        String clientOrderId = "C-" + tradeId;
        
        if (!orderRepository.existsById(orderId)) {
            com.tradingbot.infrastructure.persistence.entity.OrderEntity order = new com.tradingbot.infrastructure.persistence.entity.OrderEntity();
            order.setId(orderId);
            order.setClientOrderId(clientOrderId);
            order.setSymbol(symbol);
            order.setSide(side);
            order.setType(com.tradingbot.common.enums.OrderType.MARKET);
            order.setStrategyId("default");
            order.setQuantity(new BigDecimal(qty));
            order.setPrice(new BigDecimal(price));
            order.setStatus(com.tradingbot.common.enums.OrderStatus.FILLED);
            order.setVersion(0L);
            order.setCreatedAt(Instant.now());
            orderRepository.saveAndFlush(order);
        }
        // Вызываем сервис напрямую, так как в новой архитектуре он не слушает события Spring автоматически
        tradeService.onOrderFilled(new OrderFilledEvent(orderId, "EXT-" + tradeId, symbol, new BigDecimal(qty), new BigDecimal(price)));
        entityManager.flush();
    }
}
