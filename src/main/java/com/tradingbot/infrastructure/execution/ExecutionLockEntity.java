package com.tradingbot.infrastructure.execution;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Сущность блокировки исполнения (idempotency lock).
 *
 * <p>Используется для обеспечения идемпотентности выполнения операций,
 * предотвращая повторное выполнение одного и того же действия по одному
 * и тому же ключу идемпотентности.</p>
 *
 * <p>Типичный сценарий применения — защита исполнения ордеров и команд
 * в распределённой системе исполнения.</p>
 */
@Entity
@Table(name = "execution_lock")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionLockEntity {

    /**
     * Уникальный ключ идемпотентности (например, signalId, executionId и т.д.).
     */
    @Id
    private String idempotencyKey;

    /**
     * Текущее состояние блокировки (например: LOCKED, PROCESSED, FAILED).
     */
    private String state;

    /**
     * Время создания записи блокировки.
     */
    private Instant createdAt;
}