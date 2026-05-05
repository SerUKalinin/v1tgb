package com.tradingbot.config;

import com.tradingbot.application.risk.RiskStateStore;
import com.tradingbot.domain.position.PositionReducer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainConfig {

    @Bean
    public RiskStateStore riskStateStore() {
        return new RiskStateStore();
    }

    @Bean
    public PositionReducer positionReducer() {
        return new PositionReducer();
    }
}