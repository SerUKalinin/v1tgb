package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.infrastructure.persistence.entity.ExecutionClaimEntity;
import com.tradingbot.infrastructure.persistence.repository.ExecutionClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Адаптер для управления технической идемпотентностью выполнения.
 *
 * <p>Execution claim является частью транзакции, в которой одновременно
 * переводится Order в EXECUTING. Это исключает состояние, при котором
 * execution claim уже закоммичен, а Order остался PENDING_EXECUTION.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionClaimAdapter implements ExecutionClaimPort {

    private final ExecutionClaimRepository repository;

    /**
     * Захватывает сигнал для исполнения.
     *
     * <p>Сигнальный claim имеет собственную транзакционную границу и
     * не является частью execution claim Order.</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claimSignal(UUID signalId) {
        if (existsBySignalId(signalId)) {
            return;
        }

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

    /**
     * Захватывает executionId в текущей транзакции.
     *
     * <p>Метод намеренно использует REQUIRED: если вызывается из
     * {@code OrderExecutionHandler.claimOrder()}, execution claim и переход
     * Order {@code PENDING_EXECUTION -> EXECUTING} коммитятся атомарно.</p>
     *
     * <p>Гонка двух исполнителей разрешается уникальным ограничением БД.
     * При конфликте исключение не поглощается: текущая транзакция должна
     * полностью откатиться, после чего outbox delivery будет безопасно
     * повторена.</p>
     *
     * @param executionId идентификатор исполнения
     * @param signalId идентификатор исходного сигнала
     */
    @Override
    @Transactional
    public void claimExecution(UUID executionId, UUID signalId) {
        if (existsByExecutionId(executionId)) {
            return;
        }

        ExecutionClaimEntity entity = ExecutionClaimEntity.create(executionId, signalId);

        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (isExecutionAlreadyClaimed(e)) {
                log.debug(
                        "[EXECUTION-CLAIM] Concurrent claim detected for executionId {}. Handled via DB constraint.",
                        executionId
                );
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