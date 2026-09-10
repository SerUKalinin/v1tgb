package com.tradingbot.infrastructure.exchange.binance;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.binance.BinanceExecutionAdapter;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

public class BinanceMappingTest {

    private BinanceClient binanceClient;
    private BinanceExecutionAdapter adapter;

    @BeforeEach
    void setUp() {
        binanceClient = Mockito.mock(BinanceClient.class);
        adapter = new BinanceExecutionAdapter(binanceClient);
        adapter.setSelf(adapter);
    }

    @Test
    void testMappingLotSizeErrorToRejected() {
        UUID orderId = UUID.randomUUID();
        Order order = createOrder(orderId);

        when(binanceClient.post(anyString(), anyMap(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("400 Bad Request: {\"code\":-1013,\"msg\":\"Filter failure: LOT_SIZE\"}"));

        ExecutionResult result = adapter.placeOrder(order);

        assertEquals(ExecutionResult.Status.REJECTED, result.getStatus());
        assertEquals(orderId, result.getOrderId());
    }

    @Test
    void testMappingTimeoutToExchangeStateUnknown() {
        UUID orderId = UUID.randomUUID();
        Order order = createOrder(orderId);

        when(binanceClient.post(anyString(), anyMap(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("Read Timeout 504"));

        // doPlaceOrder выбрасывает исключение → fallback вызывается прокси,
        // но в юнит-тесте прокси нет, поэтому проверяем логику fallback вручную
        try {
            adapter.doPlaceOrder(order);
        } catch (Exception e) {
            ExecutionResult result = adapter.fallbackPlaceOrder(order, e);
            assertEquals(ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN, result.getStatus());
        }
    }

    private Order createOrder(UUID orderId) {
        return Order.createPendingExecution(
                orderId,
                "bot_" + orderId.toString().replace("-", ""),
                "BTCUSDT",
                OrderSide.BUY,
                OrderType.MARKET,
                BigDecimal.ONE,
                new BigDecimal("50000"),
                "test-strategy",
                UUID.randomUUID()
        );
    }
}
