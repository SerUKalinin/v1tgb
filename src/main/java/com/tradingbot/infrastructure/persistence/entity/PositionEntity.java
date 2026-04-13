package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "positions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PositionEntity {
    @Id
    private String symbol;

    private BigDecimal quantity;
    private BigDecimal entryPrice;
}