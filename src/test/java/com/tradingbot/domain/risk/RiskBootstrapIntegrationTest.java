package com.tradingbot.domain.risk;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class RiskBootstrapIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RiskEngine riskEngine;

    @Autowired
    private TransactionTemplate transactionTemplate;
    @Test
    void shouldWorkWithRiskCore() {
        // 1. Инициализация начального состояния
        transactionTemplate.execute(status -> {
            if (riskStateRepository.findById(RiskStateEntity.SINGLETON_ID).isEmpty()) {
                RiskStateEntity seed = new RiskStateEntity();
                seed.setId(RiskStateEntity.SINGLETON_ID);
                seed.setAvailableBalance(new BigDecimal("10000.00"));
                seed.setReservedMargin(BigDecimal.ZERO);
                seed.setTotalEquity(new BigDecimal("10000.00"));
                seed.setHalted(false);
                riskStateRepository.saveAndFlush(seed);
            }
            return null;
        });

        var initialEntity = riskStateRepository.findById(RiskStateEntity.SINGLETON_ID).orElseThrow();
        BigDecimal initialBalance = initialEntity.getAvailableBalance();

        // 2. Выполняем операции через RiskEngine (Reserve + Release)
        UUID orderId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            // Резервируем
            riskEngine.publish(new RiskEvent.CapitalReserved(
                    "BOOTSTRAP-RES-" + orderId,
                    orderId,
                    new BigDecimal("100.00")
            ));

            // Освобождаем
            riskEngine.publish(new RiskEvent.CapitalReleased(
                    "BOOTSTRAP-REL-" + orderId,
                    orderId,
                    new BigDecimal("100.00"),
                    "Integration Test"
            ));
            return null;
        });

        // 3. Проверяем результат в БД
        var updatedEntity = riskStateRepository.findById(RiskStateEntity.SINGLETON_ID).orElseThrow();

        assertThat(updatedEntity.getAvailableBalance())
                .isEqualByComparingTo(initialBalance);
        assertThat(updatedEntity.getReservedMargin())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
