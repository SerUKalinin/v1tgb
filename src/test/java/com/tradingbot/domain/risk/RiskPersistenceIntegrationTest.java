package com.tradingbot.domain.risk;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RiskPersistenceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RiskEngine riskEngine;

    @Autowired
    private RiskStateRepository riskStateRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000.00000000");

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            riskStateRepository.deleteAll();
            RiskState initialState = RiskState.builder()
                    .balance(INITIAL_BALANCE)
                    .totalEquity(INITIAL_BALANCE)
                    .halted(false)
                    .build();
            riskEngine.initialize(initialState);
        });
    }

    @Test
    void testPersistenceAcrossRestartSimulation() {
        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        BigDecimal reserveAmount = new BigDecimal("500.00");

        // 1. Reserve capital
        ExecutionContext reserveContext = new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.firstAttempt(signalId),
                BusinessContext.of(orderId.toString())
        );
        RiskDecision decision = riskEngine.reserve(reserveContext, reserveAmount);
        assertTrue(decision.isApproved());

        RiskState stateBefore = riskEngine.getState();
        assertEquals(0, INITIAL_BALANCE.subtract(reserveAmount).compareTo(stateBefore.getBalance()));
        assertEquals(0, reserveAmount.compareTo(stateBefore.getReserved()));
        assertTrue(stateBefore.getActiveReservations().containsKey(orderId));

        // 2. Simulate "Restart" — verify DB entity directly
        RiskStateEntity entity = riskStateRepository.findById(RiskStateEntity.SINGLETON_ID)
                .orElseThrow();
        assertNotNull(entity.getActiveReservations());
        assertTrue(entity.getActiveReservations().containsKey(orderId));
        assertFalse(entity.getProcessedEventIds().isEmpty());

        // 3. Release capital
        BigDecimal releaseAmount = new BigDecimal("500.00");
        ExecutionContext releaseContext = new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.firstAttempt(signalId),
                BusinessContext.of(orderId.toString())
        );
        riskEngine.release(releaseContext, releaseAmount, "Test release");

        // 4. Verify balance consistency after release
        RiskState finalState = riskEngine.getState();
        assertEquals(0, INITIAL_BALANCE.compareTo(finalState.getBalance()),
                "Balance should return to initial");
        assertEquals(0, BigDecimal.ZERO.compareTo(finalState.getReserved()),
                "Reserved should be zero");
        assertFalse(finalState.getActiveReservations().containsKey(orderId),
                "Reservation should be removed");

        // 5. Test Double Release (Idempotency)
        ExecutionContext duplicateContext = new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.firstAttempt(signalId),
                BusinessContext.of(orderId.toString())
        );
        riskEngine.release(duplicateContext, releaseAmount, "Double release test");

        RiskState stateAfterDoubleRelease = riskEngine.getState();
        assertEquals(0, INITIAL_BALANCE.compareTo(stateAfterDoubleRelease.getBalance()),
                "Balance should remain same");
        assertEquals(0, BigDecimal.ZERO.compareTo(stateAfterDoubleRelease.getReserved()),
                "Reserved should remain zero");
    }
}
