package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA-сущность для хранения информации о позициях в базе данных.
 */
@Entity
@Table(name = "positions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PositionEntity {

    @Id
    private String symbol;

    @Column(name = "strategy_id")
    private String strategyId;

    @Column(name = "net_quantity")
    private BigDecimal quantity;

    @Column(name = "avg_entry_price")
    private BigDecimal entryPrice;
    private BigDecimal realizedPnl;

    @Version
    private Long version;

    @Column(name = "last_trade_id")
    private Long lastTradeId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}