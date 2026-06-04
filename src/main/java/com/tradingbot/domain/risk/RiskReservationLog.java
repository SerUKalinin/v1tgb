package com.tradingbot.domain.risk;

import java.math.BigDecimal;
import java.util.UUID;

public record RiskReservationLog(
        UUID orderId,
        String clientOrderId,
        RiskReservationEventType eventType,
        BigDecimal amount
) {
}
