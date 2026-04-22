package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.common.enums.OrderSide;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trades",
        indexes = {
                @Index(name = "idx_trades_client_order_id", columnList = "client_order_id"),
                @Index(name = "idx_trades_exchange_trade_id", columnList = "exchange_trade_id"),
                @Index(name = "idx_trades_symbol_executed", columnList = "symbol,executed_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)    @JoinColumn(name = "order_id", nullable = false)
    private OrderEntity order;

    @Column(name = "client_order_id", nullable = false)
    private String clientOrderId;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSide side;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "exchange_trade_id", unique = true)
    private String exchangeTradeId;

    @Column(name = "strategy_id")
    private String strategyId;

    @Column(name = "commission")
    private BigDecimal commission;

    @Column(name = "commission_asset")
    private String commissionAsset;

    @Column(name = "realized_pnl")
    private BigDecimal realizedPnl;

    @Column(name = "sequence_id", insertable = false, updatable = false)
    private Long sequenceId;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}