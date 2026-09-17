package com.tradingbot.domain.risk;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskIdempotencyTest extends BaseIntegrationTest {

    @Autowired
    private RiskEngine riskEngine;

    @BeforeEach
    void setUp() {
        RiskState initialState = RiskState.builder()
                .balance(new BigDecimal("10000"))
                .totalEquity(new BigDecimal("10000"))
                .activeReservations(Map.of())
                .halted(false)
                .build();

        riskEngine.initialize(initialState);
    }

    @Test
    void testIdempotentCapitalRelease() {
        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100");

        ExecutionContext reserveContext = new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.firstAttemptForOrder(orderId),
                BusinessContext.of(orderId.toString())
        );

        // 1. Резервируем капитал
        BigDecimal initialBalance =
                riskEngine.getState().getAvailableBalance();

        riskEngine.reserve(
                reserveContext,
                amount
        );

        BigDecimal balanceAfterReserve =
                riskEngine.getState().getAvailableBalance();

        assertEquals(
                0,
                initialBalance
                        .subtract(amount)
                        .compareTo(balanceAfterReserve),
                "Баланс должен уменьшиться на сумму резерва"
        );

        assertEquals(
                0,
                amount.compareTo(
                        riskEngine.getState().getReservedMargin()
                ),
                "Резерв должен быть равен сумме резервирования"
        );

        // 2. Первое освобождение — успешно
        ExecutionContext releaseContext = new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.firstAttemptForOrder(orderId),
                BusinessContext.of(orderId.toString())
        );

        riskEngine.release(
                releaseContext,
                amount,
                "TEST_RELEASE"
        );

        BigDecimal balanceAfterFirstRelease =
                riskEngine.getState().getAvailableBalance();

        assertEquals(
                0,
                initialBalance.compareTo(balanceAfterFirstRelease),
                "Баланс должен вернуться к исходному значению"
        );

        assertEquals(
                0,
                riskEngine.getState()
                        .getReservedMargin()
                        .compareTo(BigDecimal.ZERO),
                "Резерв должен стать нулевым"
        );

        // 3. Второе освобождение того же orderId —
        //    не должно повторно менять баланс.
        ExecutionContext duplicateContext = new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.firstAttemptForOrder(orderId),
                BusinessContext.of(orderId.toString())
        );

        riskEngine.release(
                duplicateContext,
                amount,
                "TEST_RELEASE_DUPLICATE"
        );

        BigDecimal balanceAfterSecondRelease =
                riskEngine.getState().getAvailableBalance();

        assertEquals(
                0,
                balanceAfterFirstRelease
                        .compareTo(balanceAfterSecondRelease),
                "Повторное освобождение не должно менять баланс"
        );

        assertEquals(
                0,
                riskEngine.getState()
                        .getReservedMargin()
                        .compareTo(BigDecimal.ZERO),
                "Резерв не должен стать отрицательным"
        );
    }
}