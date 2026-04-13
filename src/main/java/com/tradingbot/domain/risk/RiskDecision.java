package com.tradingbot.domain.risk;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RiskDecision {
    private boolean approved;
    private String reason;
}