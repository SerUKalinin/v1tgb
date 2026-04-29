package com.tradingbot.domain.risk;

import com.tradingbot.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
public class RiskIdempotencyTest extends BaseIntegrationTest {

    @Autowired
    private RiskEngine riskEngine;

    @Autowired
    private RiskStateStore riskStateStore;

    @BeforeEach
    void setUp() {
        // Инициализируем чистое состояние с балансом 10000
        RiskState initialState = RiskState.builder()
                .balance(new BigDecimal("10000"))
                .totalEquity(new BigDecimal("10000"))
                .reserved(BigDecimal.ZERO)
                .halted(false)
                .build();
        riskEngine.initialize(initialState);
    }

    @Test
    void testIdempotentCapitalRelease() {
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100");

        // 1. Резервируем капитал
        BigDecimal initialBalance = riskStateStore.getState().getAvailableBalance();
        riskEngine.reserve(orderId, amount);

        BigDecimal balanceAfterReserve = riskStateStore.getState().getAvailableBalance();
        assertEquals(0, initialBalance.subtract(amount).compareTo(balanceAfterReserve), "Баланс должен уменьшиться на сумму резерва");
        assertEquals(0, amount.compareTo(riskStateStore.getState().getReservedMargin()), "Резерв должен быть равен сумме резервирования");

        // 2. Первое освобождение (успешное)
        riskEngine.release(orderId, amount, "TEST_RELEASE");
        BigDecimal balanceAfterFirstRelease = riskStateStore.getState().getAvailableBalance();
        assertEquals(0, initialBalance.compareTo(balanceAfterFirstRelease), "Баланс должен вернуться к исходному значению");
        assertEquals(0, riskStateStore.getState().getReservedMargin().compareTo(BigDecimal.ZERO), "Резерв должен стать нулевым");

        // 3. Второе освобождение того же orderId (должно быть проигнорировано Reducer-ом)
        riskEngine.release(orderId, amount, "TEST_RELEASE_DUPLICATE");
        BigDecimal balanceAfterSecondRelease = riskStateStore.getState().getAvailableBalance();

        assertEquals(0, balanceAfterFirstRelease.compareTo(balanceAfterSecondRelease), "Повторное освобождение не должно менять баланс");
        assertEquals(0, riskStateStore.getState().getReservedMargin().compareTo(BigDecimal.ZERO), "Резерв не должен стать отрицательным");
    }
}
