package com.tradingbot.infrastructure.execution;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExecutionLockService {

    private final ExecutionLockRepository repository;

    @Transactional
    public boolean tryClaim(String idempotencyKey) {
        return repository.insertLock(idempotencyKey) > 0;
    }

    @Transactional
    public boolean tryEnterExecuting(String idempotencyKey) {
        return repository.updateToExecuting(idempotencyKey) > 0;
    }

    @Transactional
    public boolean markExecuted(String idempotencyKey) {
        return repository.updateToExecuted(idempotencyKey) > 0;
    }

    @Transactional(readOnly = true)
    public String getLockState(String idempotencyKey) {
        return repository.findById(idempotencyKey)
                .map(ExecutionLockEntity::getState)
                .orElse(null);
    }
}
