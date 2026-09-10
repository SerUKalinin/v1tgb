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
 * Адаптер для управления технической идемпотентностью выполнения.
 *
 * <p>Реализует механизм execution/signal claim на уровне БД для предотвращения:
 * <ul>
 *     <li>дублирующего исполнения одного signalId</li>
 *     <li>дублирующего исполнения одного executionId</li>
 *     <li>race condition при параллельных попытках обработки</li>
 * </ul>
 *
 * <p>Гарантия достигается через уникальные ограничения в БД + обработку
 * {@link DataIntegrityViolationException}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionClaimAdapter implements ExecutionClaimPort {

    private final ExecutionClaimRepository repository;

    /**
     * Захватывает сигнал для исполнения.
     *
     * <p>Если сигнал уже был захвачен ранее — операция завершается без ошибок.
     *
     * @param signalId идентификатор сигнала
     */
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

    /**
     * Захватывает executionId для гарантии одноразового исполнения.
     *
     * @param executionId уникальный идентификатор исполнения
     * @param signalId    идентификатор исходного сигнала
     */
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

    /**
     * Проверяет существование execution claim по executionId.
     */
    @Override
    public boolean existsByExecutionId(UUID executionId) {
        return repository.existsByExecutionId(executionId);
    }

    /**
     * Проверяет существование execution claim по signalId.
     */
    @Override
    public boolean existsBySignalId(UUID signalId) {
        return repository.existsBySignalId(signalId);
    }

    /**
     * Определяет, является ли ошибка дубликатом claim по signalId.
     */
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

    /**
     * Определяет, является ли ошибка дубликатом claim по executionId.
     */
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