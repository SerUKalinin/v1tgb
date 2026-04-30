package com.tradingbot.domain.risk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Фасад для работы с системой рисков.
 * Делегирует выполнение RiskService.
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
