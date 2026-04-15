package com.tradingbot.domain.event;

import lombok.Getter;

@Getter
public class OrderRejectedEvent extends DomainEvent {
    private final String orderId;
    private final String reason;

    public OrderRejectedEvent(String orderId, String reason) {
        super();
        this.orderId = orderId;
        this.reason = reason;
    }
}
