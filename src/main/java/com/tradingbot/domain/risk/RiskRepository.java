package com.tradingbot.domain.risk;

import java.util.Optional;

public interface RiskRepository {
    RiskState get();
    void save(RiskState state);
    boolean isEventProcessed(java.util.UUID eventId);
    void markEventProcessed(java.util.UUID eventId, RiskState state, RiskEvent event);
}
