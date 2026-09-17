package com.tradingbot.domain.risk;

import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.risk.RiskStateStore;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.mapper.RiskStateMapper;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class RiskRestartResilienceTest {

    @Autowired
    private RiskEngine riskEngine;

    @Autowired
    private RiskStateStore riskStateStore;

    @Autowired
    private RiskStateRepository riskStateRepository;

    @Autowired
    private RiskStateMapper riskStateMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private void simulateRestart() {
        riskStateStore.clearCache();
    }

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {

            riskStateRepository.deleteAll();
            riskStateRepository.flush();

            RiskState initialState = RiskState.builder()
                    .balance(new BigDecimal("10000.00"))
                    .totalEquity(new BigDecimal("10000.00"))
                    .activeReservations(Map.of())
                    .processedEventIds(Set.of())
                    .halted(false)
                    .build();

            riskEngine.initialize(initialState);

            riskStateStore.clearCache();

            return null;
        });
    }

    /**
     * Создаёт ExecutionContext, привязанный к конкретному orderId.
     * orderId извлекается из context.business().orderId()
     * внутри RiskService.
     */
    private ExecutionContext createContext(UUID orderId) {
        UUID signalId = UUID.randomUUID();

        return new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.firstAttemptForOrder(orderId),
                BusinessContext.of(orderId.toString())
        );
    }

    @Test
    void shouldMaintainReservationsAcrossRestartScenario() {
        UUID orderId = UUID.randomUUID();
        BigDecimal reserveAmount = new BigDecimal("1500.00");

        // 1. Reserve capital
        riskEngine.reserve(
                createContext(orderId),
                reserveAmount
        );

        RiskState stateBefore =
                riskEngine.getState();

        assertThat(stateBefore.getReserved())
                .isEqualByComparingTo(reserveAmount);

        assertThat(stateBefore.getActiveReservations())
                .containsEntry(orderId, reserveAmount);

        // 2. Проверяем, что reservation действительно лежит в DB
        RiskState dbStateBeforeRestart =
                getDbState();

        assertThat(dbStateBeforeRestart.getReserved())
                .isEqualByComparingTo(reserveAmount);

        assertThat(dbStateBeforeRestart.getActiveReservations())
                .containsEntry(orderId, reserveAmount);

        // 3. Simulate Restart
        simulateRestart();

        // 4. После очистки cache состояние должно восстановиться из DB
        RiskState restoredState =
                riskEngine.getState();

        assertThat(restoredState.getReserved())
                .isEqualByComparingTo(reserveAmount);

        assertThat(restoredState.getActiveReservations())
                .containsEntry(orderId, reserveAmount);

        // 5. Release capital после "restart"
        riskEngine.release(
                createContext(orderId),
                reserveAmount,
                "ORDER_FILLED"
        );

        // 6. Verify final state
        RiskState stateAfter =
                riskEngine.getState();

        assertThat(stateAfter.getReserved())
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(stateAfter.getActiveReservations())
                .doesNotContainKey(orderId);
    }

    @Test
    void shouldBeIdempotentOnDoubleReserveAfterRestart() {
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("1000.00");

        // 1. First reserve
        riskEngine.reserve(
                createContext(orderId),
                amount
        );

        RiskState firstState =
                getDbState();

        assertThat(firstState.getReserved())
                .isEqualByComparingTo(amount);

        assertThat(firstState.getActiveReservations())
                .containsEntry(orderId, amount);

        // 2. Simulate restart
        simulateRestart();

        // 3. Second reserve with same orderId
        riskEngine.reserve(
                createContext(orderId),
                amount
        );

        // 4. Проверяем именно persistent DB state
        RiskState state =
                getDbState();

        assertThat(state.getReserved())
                .isEqualByComparingTo(amount);

        assertThat(state.getActiveReservations())
                .hasSize(1)
                .containsEntry(orderId, amount);

        long count =
                riskStateRepository.count();

        assertThat(count)
                .isEqualTo(1L);
    }

    /**
     * Читает RiskState непосредственно из persistence layer.
     * Не использует RiskStateStore и не полагается на in-memory cache.
     */
    private RiskState getDbState() {
        return transactionTemplate.execute(status -> {

            RiskStateEntity entity =
                    riskStateRepository.findById(
                            RiskStateEntity.SINGLETON_ID
                    ).orElseThrow(() ->
                            new IllegalStateException(
                                    "Risk state not found in DB"
                            )
                    );

            return riskStateMapper.toDomain(entity);
        });
    }
}