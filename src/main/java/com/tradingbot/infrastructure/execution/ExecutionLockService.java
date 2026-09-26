package com.tradingbot.infrastructure.execution;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис управления lifecycle idempotency-lock'ов исполнения.
 *
 * <p>Lifecycle:</p>
 *
 * <pre>
 * CLAIMED -> EXECUTING -> EXECUTED
 * </pre>
 *
 * <p>
 * Execution lock является техническим idempotency/ownership
 * механизмом execution lifecycle и не заменяет OrderStatus.EXECUTING.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ExecutionLockService {

    private final ExecutionLockRepository repository;

    /**
     * Попытка захвата lock в состоянии CLAIMED.
     *
     * <p>
     * Если ключ уже существует, новый lock не создаётся.
     * </p>
     *
     * @param idempotencyKey уникальный ключ execution lifecycle
     * @return true если lock был создан
     */
    @Transactional
    public boolean tryClaim(
            String idempotencyKey
    ) {

        return repository.insertLock(
                idempotencyKey
        ) > 0;
    }

    /**
     * Атомарно создаёт execution lock и переводит его
     * в EXECUTING.
     *
     * <p>
     * Метод должен вызываться только внутри уже существующей
     * transaction execution claim.
     *
     * Поэтому REQUIRED присоединяется к внешней transaction.
     * Никакого отдельного commit здесь быть не должно.
     * </p>
     *
     * <pre>
     * outer transaction:
     *
     * Order PENDING_EXECUTION -> EXECUTING
     * execution claim
     * execution_lock отсутствует
     *         ↓
     * execution_lock CLAIMED
     *         ↓
     * execution_lock EXECUTING
     *
     * COMMIT
     * </pre>
     *
     * @param idempotencyKey уникальный ключ execution lifecycle
     * @return true если lock успешно создан и переведён EXECUTING
     */
    @Transactional(
            propagation = Propagation.REQUIRED
    )
    public boolean claimForExecution(
            String idempotencyKey
    ) {

        int inserted =
                repository.insertLock(
                        idempotencyKey
                );

        if (inserted == 0) {
            return false;
        }

        int transitioned =
                repository.updateToExecuting(
                        idempotencyKey
                );

        return transitioned > 0;
    }

    /**
     * Переход уже существующего CLAIMED lock -> EXECUTING.
     *
     * <p>
     * Оставлен для существующих технических сценариев,
     * которым нужен отдельный transition.
     * </p>
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public boolean tryEnterExecuting(
            String idempotencyKey
    ) {

        return repository.updateToExecuting(
                idempotencyKey
        ) > 0;
    }

    /**
     * Завершение execution lifecycle:
     *
     * EXECUTING -> EXECUTED.
     *
     * <p>
     * Вызов из OrderExecutionCommitService находится
     * внутри commit transaction.
     * </p>
     *
     * @param idempotencyKey execution lock key
     * @return true если состояние действительно изменено
     */
    @Transactional
    public boolean markExecuted(
            String idempotencyKey
    ) {

        return repository.updateToExecuted(
                idempotencyKey
        ) > 0;
    }

    /**
     * Возвращает текущее состояние execution lock.
     *
     * @param idempotencyKey execution lock key
     * @return состояние или null если lock отсутствует
     */
    @Transactional(readOnly = true)
    public String getLockState(
            String idempotencyKey
    ) {

        return repository.findById(
                        idempotencyKey
                )
                .map(
                        ExecutionLockEntity::getState
                )
                .orElse(null);
    }
}