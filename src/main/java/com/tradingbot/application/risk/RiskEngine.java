package com.tradingbot.application.risk;

import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.domain.risk.RiskState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Facade layer over RiskService.
 * Used by application layer for risk operations.
 */
@Component
@RequiredArgsConstructor
public class RiskEngine {

    private final RiskService riskService;

    public void publish(RiskEvent event) {
        riskService.publish(event);
    }

    public RiskDecision reserve(UUID orderId, BigDecimal amount) {
        return riskService.reserve(orderId, amount);
    }

    public void release(UUID orderId, BigDecimal amount, String reason) {
        riskService.release(orderId, amount, reason);
    }

    public void release(UUID orderId) {
        riskService.release(orderId, BigDecimal.ZERO, "COMPENSATION");
    }

    public void syncBalance(BigDecimal actualBalance) {
        riskService.syncBalance(actualBalance);
    }

    public void emergencyStop(String reason) {
        riskService.emergencyStop(reason);
    }

    public void resumeTrading() {
        riskService.resumeTrading();
    }

    public void initialize(RiskState state) {
        riskService.initialize(state);
    }

    public RiskState getState() {
        return riskService.getState();
    }
}