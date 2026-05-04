package com.tradingbot.domain.risk;

import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
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
    private TransactionTemplate transactionTemplate;

    private void simulateRestart() {
        // Очищаем кэш в памяти, имитируя потерю состояния при перезагрузке
        riskStateStore.clearCache();
        // При следующем вызове riskEngine.getState() или publish() 
        // состояние будет загружено из БД через RiskRepository
    }

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            riskStateRepository.deleteAll();
            riskStateRepository.flush();
            riskStateStore.clearCache();
            riskEngine.syncBalance(new BigDecimal("10000.00"));
            return null;
        });
    }

    @Test
    void shouldMaintainReservationsAcrossRestartScenario() {
        UUID orderId = UUID.randomUUID();
        BigDecimal reserveAmount = new BigDecimal("1500.00");

        // 1. Reserve capital
        riskEngine.reserve(orderId, reserveAmount);

        // Verify initial state
        RiskState stateBefore = riskEngine.getState();
        assertThat(stateBefore.getReserved()).isEqualByComparingTo(reserveAmount);

        // 2. Simulate Restart
        simulateRestart();

        // 3. Release capital (должно загрузить состояние из БД и успешно обработать)
        riskEngine.release(orderId, reserveAmount, "ORDER_FILLED");

        // 4. Verify final state
        RiskState stateAfter = riskEngine.getState();
        assertThat(stateAfter.getReserved()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stateAfter.getActiveReservations()).doesNotContainKey(orderId);
    }

    @Test
    void shouldBeIdempotentOnDoubleReserveAfterRestart() {
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("1000.00");

        // First reserve
        riskEngine.reserve(orderId, amount);

        // Second reserve with same orderId (Idempotency check)
        riskEngine.reserve(orderId, amount);

        RiskState state = getDbState();
        assertThat(state.getReserved()).isEqualByComparingTo(amount); // Не 2000!
        assertThat(state.getActiveReservations()).hasSize(1);

        long count = riskStateRepository.count();
        assertThat(count).isEqualTo(1L);
    }

    private RiskState getDbState() {
        return transactionTemplate.execute(status -> {
            // Используем константу из Entity для поиска
            riskStateRepository.findById(RiskStateEntity.SINGLETON_ID)
                    .orElseThrow(() -> new IllegalStateException("Risk state not found in DB"));

            // Возвращаем актуальное состояние через движок (который загрузит его из репозитория)
            return riskEngine.getState();
        });
    }
}
