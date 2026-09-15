package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.infrastructure.persistence.entity.ExecutionClaimEntity;
import com.tradingbot.infrastructure.persistence.repository.ExecutionClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Адаптер для управления технической идемпотентностью выполнения.
 *
 * <p>
 * Execution claim является частью транзакционной границы,
 * в которой фиксируется право конкретного executionId владеть
 * выполнением.
 * </p>
 *
 * <p>
 * Гонка claim разрешается уникальными ограничениями БД.
 * Проигравший конкурентный INSERT считается нормальным результатом
 * идемпотентной гонки и не должен превращаться в
 * UnexpectedRollbackException для вызывающего кода.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionClaimAdapter implements ExecutionClaimPort {

    private final ExecutionClaimRepository repository;
    private final PlatformTransactionManager transactionManager;

    /**
     * Захватывает сигнал для исполнения.
     *
     * <p>
     * Signal claim имеет собственную транзакцию.
     * Если несколько потоков одновременно пытаются создать
     * claim одного signalId, первый успешно создаёт запись,
     * остальные проигрывают уникальную гонку и безопасно завершаются
     * без исключения для вызывающего кода.
     * </p>
     */
    @Override
    public void claimSignal(UUID signalId) {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );

        transactionTemplate.execute(status -> {
            if (existsBySignalId(signalId)) {
                return null;
            }

            ExecutionClaimEntity entity =
                    ExecutionClaimEntity.create(
                            signalId,
                            signalId
                    );

            try {
                repository.saveAndFlush(entity);

            } catch (DataIntegrityViolationException e) {

                /*
                 * Конкурирующий поток уже успел создать claim.
                 *
                 * После saveAndFlush текущая транзакция уже может быть
                 * помечена rollback-only, поэтому просто поглощать
                 * исключение внутри @Transactional метода нельзя.
                 *
                 * Здесь мы явно откатываем только эту короткую
                 * транзакцию. Внешний вызывающий код получает обычный
                 * idempotent no-op.
                 */
                status.setRollbackOnly();

                log.debug(
                        "[EXECUTION-CLAIM] Signal {} was claimed concurrently",
                        signalId
                );
            }

            return null;
        });
    }

    /**
     * Захватывает executionId в текущей транзакции.
     *
     * <p>
     * Метод намеренно не создаёт отдельную транзакцию:
     * execution claim и перевод Order
     * PENDING_EXECUTION -> EXECUTING
     * должны фиксироваться атомарно внутри одной транзакции.
     * </p>
     *
     * @param executionId идентификатор исполнения
     * @param signalId идентификатор исходного сигнала
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public void claimExecution(
            UUID executionId,
            UUID signalId
    ) {
        if (existsByExecutionId(executionId)) {
            return;
        }

        ExecutionClaimEntity entity =
                ExecutionClaimEntity.create(
                        executionId,
                        signalId
                );

        repository.saveAndFlush(entity);
    }

    @Override
    public boolean existsByExecutionId(UUID executionId) {
        return repository.existsByExecutionId(executionId);
    }

    @Override
    public boolean existsBySignalId(UUID signalId) {
        return repository.existsBySignalId(signalId);
    }
}