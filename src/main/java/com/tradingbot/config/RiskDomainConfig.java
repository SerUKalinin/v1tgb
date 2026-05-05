package com.tradingbot.config;

import com.tradingbot.domain.exchange.ExchangeFeasibilityPort;
import com.tradingbot.domain.exchange.OrderNormalizationService;
import com.tradingbot.domain.risk.RiskReservationLogPort;
import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.domain.risk.RiskStatePort;
import com.tradingbot.domain.risk.RiskStateReducer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RiskDomainConfig {

    @Bean
    public RiskStateReducer riskStateReducer() {
        return new RiskStateReducer();
    }

    @Bean
    public RiskService riskService(
            RiskStatePort port,
            RiskStateReducer reducer,
            RiskReservationLogPort logPort,
            ExchangeFeasibilityPort feasibilityPort,
            OrderNormalizationService normalizationService
    ) {
        return new RiskService(
                port,
                reducer,
                logPort,
                feasibilityPort,
                normalizationService
        );
    }
}