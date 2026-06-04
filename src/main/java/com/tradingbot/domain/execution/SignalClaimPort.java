package com.tradingbot.domain.execution;

import java.util.UUID;

public interface SignalClaimPort {
    boolean exists(UUID signalId);
    void claim(UUID signalId);
}
