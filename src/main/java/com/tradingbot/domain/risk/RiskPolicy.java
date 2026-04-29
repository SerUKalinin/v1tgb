package com.tradingbot.domain.risk;

import com.tradingbot.common.util.MoneyMath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Чистая бизнес-логика проверки рисков.
 * Не зависит от БД, кэша или транзакций.
 */
public class RiskPolicy {

    public static RiskDecision canReserve(RiskState state, UUID orderId, BigDecimal amount) {
        List<String> trace = new ArrayList<>();
        trace.add("Starting risk check for order " + orderId + " with amount " + amount);

        if (state.isHalted()) {
            return RiskDecision.reject(RiskDecision.Reason.HALTED, "Risk Engine is HALTED", trace);
        }

        if (MoneyMath.isLess(state.getBalance(), amount)) {
            trace.add("Decision: REJECTED - Insufficient capital. Available: " + state.getBalance());
            return RiskDecision.reject(RiskDecision.Reason.INSUFFICIENT_CAPITAL, "Insufficient capital", trace);
        }

        trace.add("Decision: APPROVED - Capital reserved");
        return RiskDecision.approve(amount, trace);
    }
}
