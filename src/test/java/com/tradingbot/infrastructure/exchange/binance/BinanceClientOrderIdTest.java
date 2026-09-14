package com.tradingbot.infrastructure.exchange.binance;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.common.util.ClientOrderIdGenerator;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.binance.BinanceExecutionAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class BinanceClientOrderIdTest {

    @Autowired
    private BinanceExecutionAdapter adapter;

    @Test
    void shouldHandleMissingClientOrderId() {
        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();

        // Order требует ненулевой clientOrderId в конструкторе,
        // поэтому создаём валидный, затем зануляем через reflection
        Order order = Order.createPendingExecution(
                orderId,
                "temp-client-id",
                "BTCUSDT",
                OrderSide.BUY,
                OrderType.MARKET,
                new BigDecimal("0.001"),
                new BigDecimal("50000"),
                "test-strategy",
                signalId
        );

        ReflectionTestUtils.setField(order, "clientOrderId", null);

        // IllegalArgumentException внутри sanitizeClientId(null)
        // → не retry-абельный → fallbackPlaceOrder → EXCHANGE_STATE_UNKNOWN
        ExecutionResult result = adapter.placeOrder(order);
        assertThat(result.getStatus()).isEqualTo(ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN);
        assertThat(result.getErrorMessage()).contains("clientOrderId must not be null");
    }

    @Test
    void shouldMaintainSameIdOnRetry() {
        UUID orderId = UUID.randomUUID();
        String clientOrderId = ClientOrderIdGenerator.generate(orderId);

        Order order = Order.createPendingExecution(
                orderId,
                clientOrderId,
                "BTCUSDT",
                OrderSide.BUY,
                OrderType.MARKET,
                new BigDecimal("0.001"),
                new BigDecimal("50000"),
                "test-strategy",
                UUID.randomUUID()
        );

        // Проверяем, что генератор детерминирован
        String secondGen = ClientOrderIdGenerator.generate(orderId);
        assertThat(clientOrderId).isEqualTo(secondGen);
    }
}
