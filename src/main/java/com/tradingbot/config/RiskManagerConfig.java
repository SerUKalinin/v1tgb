package com.tradingbot.config;

import com.tradingbot.application.risk.DefaultRiskManager;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.risk.RiskService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RiskManagerConfig {

    @Bean
    public RiskManager riskManager(RiskService riskService) {
        return new DefaultRiskManager(riskService);
    }
}