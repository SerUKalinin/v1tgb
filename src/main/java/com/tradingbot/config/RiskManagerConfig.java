package com.tradingbot.config;

import com.tradingbot.application.risk.DefaultRiskManager;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.tracing.ExecutionLogger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RiskManagerConfig {

    @Bean
    public RiskManager riskManager(
            RiskService riskService,
            RiskEngine riskEngine,
            ExecutionLogger executionLogger
    ) {
        return new DefaultRiskManager(
                riskService,
                riskEngine,
                executionLogger
        );
    }
}