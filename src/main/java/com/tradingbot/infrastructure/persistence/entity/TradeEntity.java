package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.common.enums.OrderSide;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA-сущность для хранения информации о сделках в базе данных.
 */
@Entity
@Table(name = "trades")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TradeEntity {

    /**
     * Уникальный идентификатор сделки.
     */
    @Id
    private String id;

    /**
     * Идентификатор ордера, которому принадлежит сделка.
     */
    private String orderId;

    /**
     * Торговый символ.
     */
    private String symbol;

    /**
     * Сторона сделки (покупка/продажа).
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
     * Время исполнения сделки.
     */
    private Instant executedAt;
}