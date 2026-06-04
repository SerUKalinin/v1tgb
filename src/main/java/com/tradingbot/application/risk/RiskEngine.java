package com.tradingbot.application.risk;

import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogContext;
import com.tradingbot.tracing.IdentityContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.domain.risk.RiskService;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Optional;
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

    public RiskDecision reserve(ExecutionContext context, BigDecimal amount) {
        return riskService.reserve(context, amount);
    }
    public void release(ExecutionContext context, BigDecimal amount, String reason) {
        riskService.release(context, amount, reason);
    }

    public Optional<Order> evaluateSignal(ExecutionContext context, com.tradingbot.domain.event.SignalEvent signal) {
        return riskService.evaluateSignal(context, signal);
    }
    public void release(ExecutionContext context) {
        riskService.release(context, BigDecimal.ZERO, "COMPENSATION");
    }    public void syncBalance(BigDecimal actualBalance) {
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