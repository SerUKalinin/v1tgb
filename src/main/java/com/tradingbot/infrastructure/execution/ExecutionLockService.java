package com.tradingbot.infrastructure.execution;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис управления lifecycle idempotency-lock'ов исполнения.
 *
 * <p>Обеспечивает согласованное переключение состояний выполнения операции:
 * CLAIMED → EXECUTING → EXECUTED.</p>
 *
 * <p>Используется для защиты execution pipeline от повторного запуска
 * одной и той же бизнес-операции при ретраях, конкуренции потоков
 * или повторной доставки событий.</p>
 */
@Service
@RequiredArgsConstructor
public class ExecutionLockService {

    private final ExecutionLockRepository repository;

    /**
     * Попытка захвата lock (CLAIMED).
     *
     * <p>Операция атомарна: если ключ уже существует — захват не произойдёт.</p>
     *
     * @param idempotencyKey уникальный ключ операции
     * @return true если lock успешно создан, иначе false
     */
    @Transactional
    public boolean tryClaim(String idempotencyKey) {
        return repository.insertLock(idempotencyKey) > 0;
    }

    /**
     * Переход в состояние EXECUTING.
     *
     * <p>Выполняется в отдельной транзакции для минимизации конкуренции
     * и предотвращения блокировок основного потока исполнения.</p>
     *
     * @param idempotencyKey ключ операции
     * @return true если переход выполнен успешно
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryEnterExecuting(String idempotencyKey) {
        return repository.updateToExecuting(idempotencyKey) > 0;
    }

    /**
     * Завершение выполнения операции (EXECUTED).
     *
     * @param idempotencyKey ключ операции
     * @return true если статус успешно обновлён
     */
    @Transactional
    public boolean markExecuted(String idempotencyKey) {
        return repository.updateToExecuted(idempotencyKey) > 0;
    }

    /**
     * Получение текущего состояния lock.
     *
     * @param idempotencyKey ключ операции
     * @return текущее состояние или null, если lock отсутствует
     */
    @Transactional(readOnly = true)
    public String getLockState(String idempotencyKey) {
        return repository.findById(idempotencyKey)
                .map(ExecutionLockEntity::getState)
                .orElse(null);
    }
}