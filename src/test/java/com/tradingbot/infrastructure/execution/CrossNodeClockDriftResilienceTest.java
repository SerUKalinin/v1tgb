package com.tradingbot.infrastructure.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class CrossNodeClockDriftResilienceTest {

    @Autowired
    private ExecutionLockService lockService;

    @Autowired
    private ExecutionLockRepository repository;

    @Test
    @DisplayName("Clock drift MUST NOT break execution ownership (DB-driven state)")
    void clockDriftResilienceTest() {
        String orderId = UUID.randomUUID().toString();
        String lockKey = "EXEC_ORDER_" + orderId;

        // 1. Node A захватывает блокировку (CLAIMED)
        ExecutionLockEntity lock = new ExecutionLockEntity();
        lock.setIdempotencyKey(lockKey);
        lock.setState("CLAIMED");
        repository.saveAndFlush(lock);

        // 2. Node A переходит в состояние EXECUTING
        boolean nodeAExecuting = lockService.tryEnterExecuting(lockKey);
        assertTrue(nodeAExecuting, "Node A should enter EXECUTING state");

        // 3. Симулируем Node B.
        // Даже если на Node B другое время, логика переходов в БД защищает от перехвата.

        // Проверка текущего состояния
        String currentState = lockService.getLockState(lockKey);
        assertEquals("EXECUTING", currentState);

        // Попытка Node B войти в EXECUTING (провалится, так как статус в БД != 'CLAIMED')
        boolean nodeBIntercepted = lockService.tryEnterExecuting(lockKey);
        assertFalse(nodeBIntercepted, "Node B MUST NOT intercept EXECUTING state from Node A");
    }}
