package com.tradingbot.domain.risk;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder(toBuilder = true)
public class RiskDecisionResult {
    RiskDecisionType decision;
    String reason;
    String signalId;
    String traceId;
    String state;
    Instant timestamp;
}
