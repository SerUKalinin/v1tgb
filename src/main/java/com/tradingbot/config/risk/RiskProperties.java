package com.tradingbot.config.risk;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@Data
@ConfigurationProperties(prefix = "risk")
public class RiskProperties {

    private BigDecimal maxRiskPerTrade;
    private BigDecimal maxDailyLoss;
    private BigDecimal maxPositionSize;
}