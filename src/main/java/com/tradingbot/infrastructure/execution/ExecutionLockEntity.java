package com.tradingbot.infrastructure.execution;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "execution_lock")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionLockEntity {
    @Id
    private String idempotencyKey;
    private String state;
    private Instant createdAt;
}
