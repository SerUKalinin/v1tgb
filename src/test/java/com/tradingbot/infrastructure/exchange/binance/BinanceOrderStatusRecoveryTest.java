package com.tradingbot.infrastructure.exchange.binance;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.infrastructure.binance.BinanceExecutionAdapter;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import com.tradingbot.infrastructure.execution.binance.OrderStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BinanceOrderStatusRecoveryTest {

    private BinanceClient binanceClient;
    private BinanceExecutionAdapter adapter;

    @BeforeEach
    void setUp() {

        binanceClient =
                mock(BinanceClient.class);

        adapter =
                new BinanceExecutionAdapter(
                        binanceClient
                );

        adapter.setSelf(adapter);
    }

    @Test
    void shouldQueryOrderStatusUsingSymbolAndClientOrderId() {

        when(
                binanceClient.get(
                        eq("/api/v3/order"),
                        anyMap(),
                        eq(OrderStatusResponse.class),
                        eq(true)
                )
        ).thenReturn(
                OrderStatusResponse.builder()
                        .status("FILLED")
                        .executedQty(
                                new BigDecimal("0.002")
                        )
                        .price(BigDecimal.ZERO)
                        .cummulativeQuoteQty(
                                new BigDecimal("120.00")
                        )
                        .exchangeOrderId("123456")
                        .clientOrderId("client-123")
                        .build()
        );

        ExecutionResult result =
                adapter.getOrderStatus(
                        "BTCUSDT",
                        "client-123"
                );

        assertThat(result.getStatus())
                .isEqualTo(
                        ExecutionResult.Status.FILLED
                );

        assertThat(result.getExecutedQty())
                .isEqualByComparingTo(
                        new BigDecimal("0.002")
                );

        assertThat(result.getExecutedPrice())
                .isEqualByComparingTo(
                        new BigDecimal("60000")
                );

        assertThat(result.getExchangeOrderId())
                .isEqualTo("123456");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor =
                ArgumentCaptor.forClass(Map.class);

        verify(binanceClient)
                .get(
                        eq("/api/v3/order"),
                        captor.capture(),
                        eq(OrderStatusResponse.class),
                        eq(true)
                );

        Map<String, String> params =
                captor.getValue();

        assertThat(params)
                .containsEntry(
                        "symbol",
                        "BTCUSDT"
                );

        assertThat(params)
                .containsEntry(
                        "origClientOrderId",
                        "client-123"
                );
    }

    @Test
    void shouldMapPartiallyFilledOrder() {

        when(
                binanceClient.get(
                        eq("/api/v3/order"),
                        anyMap(),
                        eq(OrderStatusResponse.class),
                        eq(true)
                )
        ).thenReturn(
                OrderStatusResponse.builder()
                        .status("PARTIALLY_FILLED")
                        .executedQty(
                                new BigDecimal("0.001")
                        )
                        .price(BigDecimal.ZERO)
                        .cummulativeQuoteQty(
                                new BigDecimal("60.00")
                        )
                        .exchangeOrderId("123457")
                        .clientOrderId("client-124")
                        .build()
        );

        ExecutionResult result =
                adapter.getOrderStatus(
                        "BTCUSDT",
                        "client-124"
                );

        assertThat(result.getStatus())
                .isEqualTo(
                        ExecutionResult.Status.PARTIALLY_FILLED
                );

        assertThat(result.getExecutedQty())
                .isEqualByComparingTo(
                        new BigDecimal("0.001")
                );

        assertThat(result.getExecutedPrice())
                .isEqualByComparingTo(
                        new BigDecimal("60000")
                );
    }

    @Test
    void shouldReturnAcceptedForOpenExchangeOrder() {

        when(
                binanceClient.get(
                        eq("/api/v3/order"),
                        anyMap(),
                        eq(OrderStatusResponse.class),
                        eq(true)
                )
        ).thenReturn(
                OrderStatusResponse.builder()
                        .status("NEW")
                        .executedQty(BigDecimal.ZERO)
                        .price(
                                new BigDecimal("60000")
                        )
                        .cummulativeQuoteQty(
                                BigDecimal.ZERO
                        )
                        .exchangeOrderId("123458")
                        .clientOrderId("client-125")
                        .build()
        );

        ExecutionResult result =
                adapter.getOrderStatus(
                        "BTCUSDT",
                        "client-125"
                );

        assertThat(result.getStatus())
                .isEqualTo(
                        ExecutionResult.Status.ACCEPTED
                );

        assertThat(result.getExecutedQty())
                .isEqualByComparingTo(
                        BigDecimal.ZERO
                );
    }

    @Test
    void shouldMapCanceledOrder() {

        when(
                binanceClient.get(
                        eq("/api/v3/order"),
                        anyMap(),
                        eq(OrderStatusResponse.class),
                        eq(true)
                )
        ).thenReturn(
                OrderStatusResponse.builder()
                        .status("CANCELED")
                        .executedQty(
                                new BigDecimal("0.001")
                        )
                        .price(
                                new BigDecimal("60000")
                        )
                        .cummulativeQuoteQty(
                                new BigDecimal("60")
                        )
                        .exchangeOrderId("123459")
                        .clientOrderId("client-126")
                        .build()
        );

        ExecutionResult result =
                adapter.getOrderStatus(
                        "BTCUSDT",
                        "client-126"
                );

        assertThat(result.getStatus())
                .isEqualTo(
                        ExecutionResult.Status.CANCELED
                );

        assertThat(result.getExchangeOrderId())
                .isEqualTo("123459");
    }

    @Test
    void shouldReturnUnknownWhenExchangeStatusLookupFails() {

        when(
                binanceClient.get(
                        eq("/api/v3/order"),
                        anyMap(),
                        eq(OrderStatusResponse.class),
                        eq(true)
                )
        ).thenThrow(
                new RuntimeException(
                        "Read Timeout 504"
                )
        );

        ExecutionResult result =
                adapter.getOrderStatus(
                        "BTCUSDT",
                        "client-timeout"
                );

        assertThat(result.getStatus())
                .isEqualTo(
                        ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN
                );
    }

    @Test
    void shouldUseCumulativeQuoteQtyForAverageExecutionPrice() {

        when(
                binanceClient.get(
                        eq("/api/v3/order"),
                        anyMap(),
                        eq(OrderStatusResponse.class),
                        eq(true)
                )
        ).thenReturn(
                OrderStatusResponse.builder()
                        .status("FILLED")
                        .executedQty(
                                new BigDecimal("0.005")
                        )
                        .price(
                                new BigDecimal("999999")
                        )
                        .cummulativeQuoteQty(
                                new BigDecimal("305.25")
                        )
                        .exchangeOrderId("123460")
                        .clientOrderId("client-127")
                        .build()
        );

        ExecutionResult result =
                adapter.getOrderStatus(
                        "ETHUSDT",
                        "client-127"
                );

        assertThat(result.getStatus())
                .isEqualTo(
                        ExecutionResult.Status.FILLED
                );

        assertThat(result.getExecutedPrice())
                .isEqualByComparingTo(
                        new BigDecimal("61050")
                );
    }
}