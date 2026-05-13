package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.infrastructure.persistence.entity.ExecutionClaimEntity;
import com.tradingbot.infrastructure.persistence.repository.ExecutionClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

/**
 * <h1>ExecutionClaimAdapter</h1>
 *
 * <p>Адаптер для управления технической идемпотентностью исполнения.
 * Гарантирует, что конкретный executionId (попытка) будет выполнен только один раз.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionClaimAdapter implements ExecutionClaimPort {

    private final ExecutionClaimRepository repository;

    @Override
    @Transactional(propagation = REQUIRES_NEW)
    public void claimSignal(UUID signalId) {
        if (existsBySignalId(signalId)) {
            return;
        }

        // При клейме сигнала используем его же как executionId для обеспечения обязательности полей
        ExecutionClaimEntity entity = ExecutionClaimEntity.create(signalId, signalId);

        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (isSignalAlreadyClaimed(e)) {
                return;
            }
            throw e;
        }
    }

    @Override
    @Transactional(propagation = REQUIRES_NEW)
    public void claimExecution(UUID executionId, UUID signalId) {
        if (existsByExecutionId(executionId)) {
            return;
        }

        ExecutionClaimEntity entity = ExecutionClaimEntity.create(executionId, signalId);

        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (isExecutionAlreadyClaimed(e)) {
                log.debug("[EXECUTION-CLAIM] Concurrent claim detected for executionId {}. Handled via DB constraint.", executionId);
                return;
            }
            throw e;
        }
    }

    @Override
    public boolean existsByExecutionId(UUID executionId) {
        return repository.existsByExecutionId(executionId);
    }

    @Override
    public boolean existsBySignalId(UUID signalId) {
        return repository.existsBySignalId(signalId);
    }

    private boolean isSignalAlreadyClaimed(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause == null || cause.getMessage() == null) {
            return false;
        }
        String message = cause.getMessage().toLowerCase();
        return message.contains("uq_execution_claims_signal_id")
                || message.contains("execution_claims_signal_id")
                || (message.contains("unique") && message.contains("signal_id"));
    }

    private boolean isExecutionAlreadyClaimed(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause == null || cause.getMessage() == null) {
            return false;
        }
        String message = cause.getMessage().toLowerCase();
        return message.contains("uq_execution_claims_execution_id")
                || message.contains("execution_claims_execution_id")
                || (message.contains("unique") && message.contains("execution_id"));
    }
}
