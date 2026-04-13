package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.common.enums.OrderSide;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trades")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class TradeEntity {
    @Id
    private String id;

    private String orderId;
    private String symbol;

    @Enumerated(EnumType.STRING)
    private OrderSide side;

    private BigDecimal quantity;
    private BigDecimal price;
    private Instant executedAt;
}