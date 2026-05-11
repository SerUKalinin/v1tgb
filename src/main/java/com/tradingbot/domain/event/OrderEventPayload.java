package com.tradingbot.domain.event;

import lombok.Builder;
import lombok.Value;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Стабильное DTO для событий Outbox.
 * Изолирует потребителей от изменений в JPA сущностях.
 */
@Value
@Builder
public class OrderEventPayload {
    UUID orderId;
    String clientOrderId;
    String symbol;
    BigDecimal quantity;
    BigDecimal price;
    String status;
    Instant timestamp;
    String strategyId;
    String signalId;
    UUID causationId;
    UUID correlationId;
}
