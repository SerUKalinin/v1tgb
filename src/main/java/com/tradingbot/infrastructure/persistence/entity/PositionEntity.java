package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA-сущность для хранения информации о позициях в базе данных.
 */
@Entity
@Table(name = "positions",
        uniqueConstraints = @UniqueConstraint(name = "uq_positions_symbol_strategy", columnNames = {"symbol", "strategy_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    @Column(name = "net_quantity", nullable = false)
    private BigDecimal quantity;

    @Column(name = "avg_entry_price", nullable = false)
    private BigDecimal entryPrice;

    @Column(name = "realized_pnl")
    private BigDecimal realizedPnl;

    @Column(name = "last_trade_id")
    private Long lastTradeId;

    @Column(name = "stop_loss")
    private BigDecimal stopLoss;

    @Column(name = "take_profit")
    private BigDecimal takeProfit;

    @Builder.Default
    @Column(nullable = false)
    private String status = "OPEN";
    @Column(name = "close_request_id")    private java.util.UUID closeRequestId;

    @Version
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}