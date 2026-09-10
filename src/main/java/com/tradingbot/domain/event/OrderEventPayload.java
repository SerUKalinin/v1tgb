package com.tradingbot.domain.event;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Стабильный DTO payload для Outbox событий ордера.
 *
 * <p>Используется как контракт между доменным слоем и инфраструктурным Outbox,
 * обеспечивая изоляцию от изменений JPA-сущностей и внутренних моделей.</p>
 *
 * <p>Свойства объекта являются immutable и гарантируют консистентность
 * при публикации событий.</p>
 */
@Value
@Builder
public class OrderEventPayload {

    /**
     * Идентификатор ордера в доменной модели.
     */
    UUID orderId;

    /**
     * Client order id (идентификатор на стороне биржи).
     */
    String clientOrderId;

    /**
     * Торговый символ (например BTCUSDT).
     */
    String symbol;

    /**
     * Количество актива в ордере.
     */
    BigDecimal quantity;

    /**
     * Цена исполнения ордера.
     */
    BigDecimal price;

    /**
     * Статус ордера в момент публикации события.
     */
    String status;

    /**
     * Временная метка события.
     */
    Instant timestamp;

    /**
     * Идентификатор стратегии, инициировавшей ордер.
     */
    String strategyId;

    /**
     * Идентификатор сигнала, приведшего к созданию ордера.
     */
    String signalId;

    /**
     * Causation ID (цепочка причинности события).
     */
    UUID causationId;

    /**
     * Correlation ID (сквозная корреляция между системами).
     */
    UUID correlationId;
}