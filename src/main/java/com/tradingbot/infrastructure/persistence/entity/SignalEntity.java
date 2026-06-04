package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.common.enums.SignalType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "signals",
        indexes = {
                @Index(name = "idx_signals_timestamp", columnList = "timestamp"),
                @Index(name = "idx_signals_strategy_id", columnList = "strategy_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignalEntity {

 @Id
 private UUID id;

 @Column(nullable = false)
 private String symbol;

 @Enumerated(EnumType.STRING)
 @Column(nullable = false)
 private SignalType type;

 @Column(nullable = false, precision = 38, scale = 18)
 private BigDecimal price;

    @Column(name = "take_profit_1")
    private BigDecimal takeProfit1;

    @Column(name = "take_profit_2")
    private BigDecimal takeProfit2;

    @Column(name = "stop_loss")
    private BigDecimal stopLoss;

    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

 @Column(nullable = false)
 private Instant timestamp;

 @PrePersist
 protected void onCreate() {
    if (id == null) {
        id = UUID.randomUUID();
 }

    if (createdAt == null) {
        createdAt = Instant.now();
 }

    if (timestamp == null) {
        timestamp = Instant.now();
 }
 }
}
