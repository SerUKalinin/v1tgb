package com.tradingbot.domain.risk;

import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class RiskBootstrapIntegrationTest {

    @Autowired
    private RiskEngine riskEngine;

    @Autowired
    private RiskStateRepository riskStateRepository;

    @Test
    void shouldWorkWithRiskCore() {
        if (riskStateRepository.findById("risk_core").isEmpty()) {
            RiskStateEntity seed = new RiskStateEntity();
            seed.setId("risk_core");
            seed.setAvailableBalance(new BigDecimal("10000.00"));
            seed.setReservedMargin(BigDecimal.ZERO);
            seed.setTotalEquity(new BigDecimal("10000.00"));
            seed.setHalted(false);
            seed.setVersion(0L);
            seed.setUpdatedAt(java.time.Instant.now());
            riskStateRepository.saveAndFlush(seed);
        }

        var initialEntity = riskStateRepository.findById("risk_core").get();
        BigDecimal initialBalance = initialEntity.getAvailableBalance();

        // 2. Выполняем операции через RiskEngine
        UUID orderId = UUID.randomUUID();
        riskEngine.publish(new RiskEvent.CapitalReserved(
                UUID.randomUUID().toString(),
                orderId,
                new BigDecimal("100.00")
        ));

        riskEngine.publish(new RiskEvent.CapitalReleased(
                UUID.randomUUID().toString(),
                orderId,
                new BigDecimal("100.00"),
                "Integration Test"
        ));

        // 3. Проверяем результат в БД
        riskStateRepository.flush();
        var updatedEntity = riskStateRepository.findById("risk_core").get();
        assertThat(updatedEntity.getAvailableBalance()).isEqualByComparingTo(initialBalance);
        assertThat(updatedEntity.getReservedMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
