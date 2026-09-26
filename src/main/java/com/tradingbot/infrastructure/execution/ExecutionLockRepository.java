package com.tradingbot.infrastructure.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий для управления execution idempotency lock.
 *
 * <p>Lifecycle:</p>
 *
 * <pre>
 * CLAIMED -> EXECUTING -> EXECUTED
 * </pre>
 *
 * <p>
 * INSERT lock должен быть идемпотентным:
 * повторная попытка создания уже существующего lock
 * не должна создавать вторую запись.
 *
 * <p>
 * Используем SQL MERGE вместо PostgreSQL-specific
 * INSERT ... ON CONFLICT, чтобы один и тот же integration
 * path корректно работал и на PostgreSQL, и на H2 test profile.
 * </p>
 */
@Repository
public interface ExecutionLockRepository
        extends JpaRepository<ExecutionLockEntity, String> {

    /**
     * Идемпотентно создаёт lock в состоянии CLAIMED.
     *
     * <p>
     * Если idempotency_key уже существует,
     * существующая строка не изменяется.
     *
     * <p>
     * MERGE выбран вместо ON CONFLICT:
     *
     * - PostgreSQL 16 поддерживает MERGE;
     * - H2 поддерживает MERGE;
     * - семантика сохраняется: insert-if-absent.
     * </p>
     *
     * @param key execution lifecycle idempotency key
     * @return количество изменённых строк
     */
    @Modifying
    @Query(
            value = """
                    MERGE INTO execution_lock AS target
                    USING (
                        VALUES (
                            :key,
                            'CLAIMED',
                            CURRENT_TIMESTAMP
                        )
                    ) AS source(
                        idempotency_key,
                        state,
                        created_at
                    )
                    ON target.idempotency_key = source.idempotency_key
                    WHEN NOT MATCHED THEN
                        INSERT (
                            idempotency_key,
                            state,
                            created_at
                        )
                        VALUES (
                            source.idempotency_key,
                            source.state,
                            source.created_at
                        )
                    """,
            nativeQuery = true
    )
    int insertLock(
            @Param("key") String key
    );

    /**
     * CLAIMED -> EXECUTING.
     *
     * <p>
     * Conditional update является частью execution
     * ownership protocol.
     *
     * @param key execution lifecycle idempotency key
     * @return 1 если transition выполнен, иначе 0
     */
    @Modifying
    @Query(
            value = """
                    UPDATE execution_lock
                    SET state = 'EXECUTING'
                    WHERE idempotency_key = :key
                      AND state = 'CLAIMED'
                    """,
            nativeQuery = true
    )
    int updateToExecuting(
            @Param("key") String key
    );

    /**
     * EXECUTING -> EXECUTED.
     *
     * <p>
     * Conditional update защищает terminal execution state
     * от повторного завершения.
     *
     * @param key execution lifecycle idempotency key
     * @return 1 если transition выполнен, иначе 0
     */
    @Modifying
    @Query(
            value = """
                    UPDATE execution_lock
                    SET state = 'EXECUTED'
                    WHERE idempotency_key = :key
                      AND state = 'EXECUTING'
                    """,
            nativeQuery = true
    )
    int updateToExecuted(
            @Param("key") String key
    );
}