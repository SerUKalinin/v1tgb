package com.tradingbot.application.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import java.math.BigDecimal;

public class SystemEvents {

    @Getter
    @RequiredArgsConstructor
    public static class ColdStartDetectedEvent {
        private final BigDecimal exchangeBalance;
    }

    @Getter
    @RequiredArgsConstructor
    public static class StandardReconciliationRequestedEvent {
        private final BigDecimal internalBalance;
        private final BigDecimal exchangeBalance;
    }

    public static class SystemReadyEvent {}
}
