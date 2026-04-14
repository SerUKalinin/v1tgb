package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * JPA-сущность для хранения информации о позициях в базе данных.
 */
@Entity
@Table(name = "positions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PositionEntity {

    /**
     * Торговый символ (является первичным ключом).
     */
    @Id
    private String symbol;

    /**
     * Количество базовой валюты в позиции.
     */
    private BigDecimal quantity;

    /**
     * Средняя цена входа в позицию.
     */
    private BigDecimal entryPrice;
}