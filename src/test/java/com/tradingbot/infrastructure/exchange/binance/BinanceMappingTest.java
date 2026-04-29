package com.tradingbot.infrastructure.exchange.binance;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
    }

    @Test
    void testMappingLotSizeErrorToRejected() {
        UUID orderId = UUID.randomUUID();
        ApprovedOrder order = ApprovedOrder.builder()
                .orderId(orderId)
                .clientOrderId("bot_" + orderId.toString().replace("-", ""))
                .symbol("BTCUSDT")
                .side(com.tradingbot.common.enums.OrderSide.BUY)
                .type(com.tradingbot.common.enums.OrderType.MARKET)
                .quantity(java.math.BigDecimal.ONE)
                .build();

        when(binanceClient.post(anyString(), anyMap(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("400 Bad Request: {\"code\":-1013,\"msg\":\"Filter failure: LOT_SIZE\"}"));

        ExecutionResult result = adapter.placeOrder(order);

        assertEquals(ExecutionResult.Status.REJECTED, result.getStatus());
        assertEquals(orderId, result.getOrderId());
    }

    @Test
    void testMappingTimeoutToTimeoutStatus() {
        UUID orderId = UUID.randomUUID();
        ApprovedOrder order = ApprovedOrder.builder()
                .orderId(orderId)
                .clientOrderId("bot_" + orderId.toString().replace("-", ""))
                .symbol("BTCUSDT")
                .side(com.tradingbot.common.enums.OrderSide.BUY)
                .type(com.tradingbot.common.enums.OrderType.MARKET)
                .quantity(java.math.BigDecimal.ONE)
                .build();

        when(binanceClient.post(anyString(), anyMap(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("Read Timeout 504"));

        ExecutionResult result = adapter.placeOrder(order);

        assertEquals(ExecutionResult.Status.TIMEOUT, result.getStatus());
    }}
