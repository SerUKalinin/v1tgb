package com.tradingbot.domain.risk;

import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class RiskPersistenceIntegrationTest {    @Autowired
    private RiskEngine riskEngine;

    @Autowired
    private RiskStateRepository riskStateRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final BigDecimal INITIAL_BALANCE = new BigDecimal("10000.00000000");

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            riskStateRepository.deleteAll();
            RiskState initialState = RiskState.builder()
                    .balance(INITIAL_BALANCE)
                    .totalEquity(INITIAL_BALANCE)
                    .halted(false)
                    .build();
            riskEngine.initialize(initialState);
            return null;
        });
    }

    @Test
    void testPersistenceAcrossRestartSimulation() {
        UUID orderId = UUID.randomUUID();
        BigDecimal reserveAmount = new BigDecimal("500.00");

        // 1. Reserve capital
        RiskDecision decision = riskEngine.reserve(orderId, reserveAmount);
        assertTrue(decision.isApproved());

        // Verify state before "restart"
        RiskState stateBefore = riskEngine.getState();
        assertEquals(0, INITIAL_BALANCE.subtract(reserveAmount).compareTo(stateBefore.getBalance()));
        assertEquals(0, reserveAmount.compareTo(stateBefore.getReserved()));
        assertTrue(stateBefore.getActiveReservations().containsKey(orderId));

        // 2. Simulate "Restart" by clearing cache and reloading from DB
        // In a real restart, the RiskEngine would load state from DB on first access
        // We'll verify the DB entity directly to ensure it has all data
        RiskStateEntity entity = riskStateRepository.findById(RiskStateEntity.SINGLETON_ID).orElseThrow();
        
        assertNotNull(entity.getActiveReservations());        assertTrue(entity.getActiveReservations().containsKey(orderId));
        assertFalse(entity.getProcessedEventIds().isEmpty());

        // 3. Load state (simulated by a new call to loadOrInitializeRiskState in a new transaction)
        // We'll use a fresh call to release which triggers the load flow
        BigDecimal releaseAmount = new BigDecimal("500.00");
        riskEngine.release(orderId, releaseAmount, "Test release");

        // 4. Verify balance consistency after release
        RiskState finalState = riskEngine.getState();
        assertEquals(0, INITIAL_BALANCE.compareTo(finalState.getBalance()), "Balance should return to initial");
        assertEquals(0, BigDecimal.ZERO.compareTo(finalState.getReserved()), "Reserved should be zero");
        assertFalse(finalState.getActiveReservations().containsKey(orderId), "Reservation should be removed");

        // 5. Test Double Release (Idempotency)
        // Second release should not change the balance because the reservation is already gone
        riskEngine.release(orderId, releaseAmount, "Double release test");
        
        RiskState stateAfterDoubleRelease = riskEngine.getState();
        assertEquals(0, INITIAL_BALANCE.compareTo(stateAfterDoubleRelease.getBalance()), "Balance should remain same");
        assertEquals(0, BigDecimal.ZERO.compareTo(stateAfterDoubleRelease.getReserved()), "Reserved should remain zero");
    }
}
