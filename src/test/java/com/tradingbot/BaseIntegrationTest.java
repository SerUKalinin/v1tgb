package com.tradingbot;

import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Autowired
    protected RiskStateRepository riskStateRepository;

    @BeforeEach
    @Transactional
    void setUpRiskState() {
        if (riskStateRepository.findById("risk_core").isEmpty()) {
            RiskStateEntity riskState = new RiskStateEntity();
            riskState.setId("risk_core");
            riskState.setTotalEquity(BigDecimal.ZERO);
            riskState.setAvailableBalance(BigDecimal.ZERO);
            riskState.setReservedMargin(BigDecimal.ZERO);
            riskState.setHalted(false);
            riskState.setVersion(0L);
            riskState.setUpdatedAt(Instant.now());
            riskStateRepository.saveAndFlush(riskState);
        }
    }
}
