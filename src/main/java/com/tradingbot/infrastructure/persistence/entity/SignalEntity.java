package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.common.enums.SignalType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "signals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalEntity {    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    
    @Enumerated(EnumType.STRING)
    private SignalType type;
    
    private BigDecimal price;
    private BigDecimal takeProfit1;
    private BigDecimal takeProfit2;
    private BigDecimal stopLoss;
    private String strategyId;
    private Instant createdAt;
    private Instant timestamp;
}
