package com.tradingbot.infrastructure.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий для управления idempotency-lock'ами исполнения.
 *
 * <p>Обеспечивает атомарную фиксацию стадий выполнения операции
 * через optimistic/conditional updates на уровне БД.</p>
 *
 * <p>Используется для защиты от повторного выполнения одной и той же
 * бизнес-команды (signal/order/execution pipeline).</p>
 */
@Repository
public interface ExecutionLockRepository extends JpaRepository<ExecutionLockEntity, String> {

    /**
     * Создаёт lock-запись в состоянии CLAIMED, если её ещё не существует.
     *
     * <p>Использует ON CONFLICT DO NOTHING для обеспечения идемпотентности
     * на уровне базы данных.</p>
     *
     * @param key idempotency key (уникальный ключ операции)
     * @return количество вставленных строк (1 если создан, 0 если уже существует)
     */
    @Modifying
    @Query(value = """
        INSERT INTO execution_lock (idempotency_key, state, created_at)
        VALUES (:key, 'CLAIMED', CURRENT_TIMESTAMP)
        ON CONFLICT (idempotency_key) DO NOTHING
        """, nativeQuery = true)
    int insertLock(@Param("key") String key);

    /**
     * Переводит lock в состояние EXECUTING.
     *
     * <p>Операция выполняется только если текущий статус CLAIMED.</p>
     *
     * @param key idempotency key
     * @return количество обновлённых строк (1 если переход успешен)
     */
    @Modifying
    @Query(value = """
        UPDATE execution_lock
        SET state = 'EXECUTING'
        WHERE idempotency_key = :key
        AND state = 'CLAIMED'
        """, nativeQuery = true)
    int updateToExecuting(@Param("key") String key);

    /**
     * Переводит lock в состояние EXECUTED после успешного завершения операции.
     *
     * <p>Операция выполняется только если текущий статус EXECUTING.</p>
     *
     * @param key idempotency key
     * @return количество обновлённых строк (1 если переход успешен)
     */
    @Modifying
    @Query(value = """
        UPDATE execution_lock
        SET state = 'EXECUTED'
        WHERE idempotency_key = :key
        AND state = 'EXECUTING'
        """, nativeQuery = true)
    int updateToExecuted(@Param("key") String key);
}