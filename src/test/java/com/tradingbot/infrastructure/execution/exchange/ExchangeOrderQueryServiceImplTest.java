package com.tradingbot.infrastructure.execution.exchange;

import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExchangeOrderQueryServiceImplTest {

    private ExecutionPort executionPort;
    private BinanceClient binanceClient;

    private ExchangeOrderQueryServiceImpl service;

    @BeforeEach
    void setUp() {

        executionPort =
                mock(ExecutionPort.class);

        binanceClient =
                mock(BinanceClient.class);

        service =
                new ExchangeOrderQueryServiceImpl(
                        executionPort,
                        binanceClient
                );
    }

    @Test
    void shouldDelegateOrderStatusLookupToExecutionPort() {

        ExecutionResult expected =
                ExecutionResult.filled(
                        null,
                        "123456",
                        "trade-1",
                        "BTCUSDT",
                        com.tradingbot.common.enums.OrderSide.BUY,
                        new BigDecimal("0.002"),
                        new BigDecimal("60000"),
                        BigDecimal.ZERO,
                        "USDT",
                        "client-123"
                );

        when(
                executionPort.getOrderStatus(
                        "BTCUSDT",
                        "client-123"
                )
        ).thenReturn(expected);

        ExecutionResult actual =
                service.getOrderStatus(
                        "BTCUSDT",
                        "client-123"
                );

        assertThat(actual)
                .isSameAs(expected);

        verify(
                executionPort,
                times(1)
        ).getOrderStatus(
                "BTCUSDT",
                "client-123"
        );
    }

    @Test
    void shouldReadAvailableBalanceFromBinanceAccount() {

        Map<String, Object> accountInfo =
                Map.of(
                        "balances",
                        List.of(
                                Map.of(
                                        "asset",
                                        "USDT",
                                        "free",
                                        "9970.25"
                                ),
                                Map.of(
                                        "asset",
                                        "BTC",
                                        "free",
                                        "0.25"
                                )
                        )
                );

        when(
                binanceClient.getAccountInfo()
        ).thenReturn(accountInfo);

        BigDecimal result =
                service.getAvailableBalance(
                        "USDT"
                );

        assertThat(result)
                .isEqualByComparingTo(
                        new BigDecimal("9970.25")
                );
    }

    @Test
    void shouldMatchAssetCaseInsensitively() {

        Map<String, Object> accountInfo =
                Map.of(
                        "balances",
                        List.of(
                                Map.of(
                                        "asset",
                                        "USDT",
                                        "free",
                                        "10000"
                                )
                        )
                );

        when(
                binanceClient.getAccountInfo()
        ).thenReturn(accountInfo);

        BigDecimal result =
                service.getAvailableBalance(
                        "usdt"
                );

        assertThat(result)
                .isEqualByComparingTo(
                        new BigDecimal("10000")
                );
    }
}