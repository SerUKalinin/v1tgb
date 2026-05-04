package com.tradingbot.infrastructure.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ExecutionLockRepository extends JpaRepository<ExecutionLockEntity, String> {

    @Modifying
    @Query(value = """
        INSERT INTO execution_lock (idempotency_key, state, created_at)
        VALUES (:key, 'CLAIMED', CURRENT_TIMESTAMP)
        ON CONFLICT (idempotency_key) DO NOTHING
        """, nativeQuery = true)
    int insertLock(@Param("key") String key);

    @Modifying
    @Query(value = """
        UPDATE execution_lock
        SET state = 'EXECUTING'
        WHERE idempotency_key = :key
        AND state = 'CLAIMED'
        """, nativeQuery = true)
    int updateToExecuting(@Param("key") String key);

    @Modifying
    @Query(value = """
        UPDATE execution_lock
        SET state = 'EXECUTED'
        WHERE idempotency_key = :key
        AND state = 'EXECUTING'
        """, nativeQuery = true)
    int updateToExecuted(@Param("key") String key);
}
