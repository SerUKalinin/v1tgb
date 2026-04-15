package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PositionId implements Serializable {
    private String symbol;
    private String strategyId;
}
