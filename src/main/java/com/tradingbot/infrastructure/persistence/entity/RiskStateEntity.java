package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "risk_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskStateEntity {

    @Id
    private String id; // "risk_core"

    @Column(name = "available_balance", nullable = false, precision = 18, scale = 8)
    private BigDecimal availableBalance;

    @Column(name = "reserved_margin", nullable = false, precision = 18, scale = 8)
    private BigDecimal reservedMargin;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal totalEquity;
    @Column(nullable = false)
    private boolean halted;

    @Version
    private Long version;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
