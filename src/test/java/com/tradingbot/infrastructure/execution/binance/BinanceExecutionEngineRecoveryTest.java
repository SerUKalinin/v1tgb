package com.tradingbot.infrastructure.execution.binance;

import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BinanceExecutionEngineRecoveryTest {

    private ExecutionPort executionPort;
    private OrderRepository orderRepository;

    private BinanceExecutionEngine engine;

    @BeforeEach
    void setUp() {

        executionPort =
                mock(ExecutionPort.class);

        orderRepository =
                mock(OrderRepository.class);

        engine =
                new BinanceExecutionEngine(
                        executionPort,
                        orderRepository
                );
    }

    @Test
    void shouldVerifyOrderUsingSymbolFromPersistedOrder() {

        String clientOrderId =
                "client-123";

        OrderEntity entity =
                mock(OrderEntity.class);

        when(
                entity.getSymbol()
        ).thenReturn("BTCUSDT");

        when(
                orderRepository.findByClientOrderId(
                        clientOrderId
                )
        ).thenReturn(
                Optional.of(entity)
        );

        ExecutionResult expected =
                ExecutionResult.filled(
                        null,
                        "123456",
                        "trade-123",
                        "BTCUSDT",
                        com.tradingbot.common.enums.OrderSide.BUY,
                        new java.math.BigDecimal("0.001"),
                        new java.math.BigDecimal("60000"),
                        java.math.BigDecimal.ZERO,
                        "USDT",
                        clientOrderId
                );

        when(
                executionPort.getOrderStatus(
                        "BTCUSDT",
                        clientOrderId
                )
        ).thenReturn(expected);

        ExecutionResult actual =
                engine.verifyOrder(
                        clientOrderId
                );

        assertThat(actual)
                .isSameAs(expected);

        verify(
                executionPort,
                times(1)
        ).getOrderStatus(
                "BTCUSDT",
                clientOrderId
        );
    }

    @Test
    void shouldReturnUnknownWhenOrderDoesNotExistInDatabase() {

        String clientOrderId =
                "missing-client-id";

        when(
                orderRepository.findByClientOrderId(
                        clientOrderId
                )
        ).thenReturn(
                Optional.empty()
        );

        ExecutionResult result =
                engine.verifyOrder(
                        clientOrderId
                );

        assertThat(result.getStatus())
                .isEqualTo(
                        ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN
                );

        verify(
                executionPort,
                never()
        ).getOrderStatus(
                anyString(),
                anyString()
        );
    }
}