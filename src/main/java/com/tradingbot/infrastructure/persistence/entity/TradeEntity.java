package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.common.enums.OrderSide;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trades",
        indexes = {
                @Index(name = "idx_trades_client_order_id", columnList = "clientOrderId"),
                @Index(name = "idx_trades_exchange_trade_id", columnList = "exchangeTradeId"),
                @Index(name = "idx_trades_symbol_executed", columnList = "symbol,executedAt")
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

    private String orderId;

    @Column(nullable = false)
    private String clientOrderId;

    private String symbol;

    @Enumerated(EnumType.STRING)
    private OrderSide side;

    private BigDecimal quantity;

    private BigDecimal price;

    private String externalTradeId;

    @Column(unique = true)
    private String exchangeTradeId;

    private String strategyId;

    private BigDecimal netQuantity;

    private BigDecimal avgEntryPrice;

    private BigDecimal realizedPnl;

    private BigDecimal commission;
    private String commissionAsset;

    @Column(insertable = false, updatable = false)
    private Long sequenceId;

    private Instant executedAt;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}