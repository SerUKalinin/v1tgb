package com.tradingbot.application.service;

import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.service.execution.PositionRebuildService;
import com.tradingbot.application.service.execution.TradeService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
    private OrderRepository orderRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @MockBean
    private OutboxService outboxService;

    @MockBean
    private RiskEngine riskEngine;

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

        assertThat(pos.getQuantity())
                .isEqualByComparingTo(new BigDecimal("0.8"));
        assertThat(pos.getEntryPrice())
                .isEqualByComparingTo(new BigDecimal("55000"));
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
        UUID orderId = UUID.nameUUIDFromBytes(
                tradeId.getBytes(StandardCharsets.UTF_8));
        UUID signalId = UUID.nameUUIDFromBytes(
                ("signal-" + tradeId).getBytes(StandardCharsets.UTF_8));
        String clientOrderId = "C-" + tradeId;

        if (!orderRepository.existsById(orderId)) {
            OrderEntity order = OrderEntity.builder()
                    .id(orderId)
                    .clientOrderId(clientOrderId)
                    .symbol(symbol)
                    .side(side)
                    .type(OrderType.MARKET)
                    .quantity(new BigDecimal(qty))
                    .price(new BigDecimal(price))
                    .strategyId("default")
                    .signalId(signalId)
                    .status(OrderStatus.FILLED)
                    .createdAt(Instant.now())
                    .build();
            orderRepository.saveAndFlush(order);
        }

        // Собираем полноценный контекст для OrderFilledEvent
        IdentityContext identity = IdentityContext.of(signalId);
        ExecutionAttemptContext attempt = ExecutionAttemptContext.firstAttempt(signalId);
        BusinessContext business = BusinessContext.of(orderId.toString());

        OrderFilledEvent event = new OrderFilledEvent(
                identity, attempt, business,
                orderId,
                "EXT-" + tradeId,
                symbol,
                new BigDecimal(qty),
                new BigDecimal(price));

        tradeService.onOrderFilled(event);
        entityManager.flush();
    }
}
