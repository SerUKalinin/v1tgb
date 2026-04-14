package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.common.enums.OrderSide;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA-сущность для хранения информации об ордерах в базе данных.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderEntity {

    /**
     * Уникальный идентификатор ордера.
     */
    @Id
    private String id;

    /**
     * Торговый символ.
     */
    private String symbol;

    /**
     * Сторона ордера (покупка/продажа).
     */
    @Enumerated(EnumType.STRING)
    private OrderSide side;

    /**
     * Количество базовой валюты.
     */
    private BigDecimal quantity;

    /**
     * Цена исполнения.
     */
    private BigDecimal price;

    /**
     * Время создания ордера.
     */
    private Instant createdAt;
}