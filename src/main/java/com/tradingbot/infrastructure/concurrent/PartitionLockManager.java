package com.tradingbot.infrastructure.concurrent;

import org.springframework.stereotype.Component;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Менеджер сегментных (striped) локов для уменьшения конкуренции
 * при конкурентном доступе к разделяемым ключам.
 *
 * <p>Реализует технику lock striping: множество логических ключей
 * отображаются на ограниченное количество ReentrantLock,
 * что снижает накладные расходы по сравнению с полноценной
 * блокировкой на каждый ключ.
 *
 * <p>Используется для защиты критических секций, где важно
 * обеспечить взаимное исключение по ключу (например, symbol, orderId и т.д.).
 */
@Component
public class PartitionLockManager {

    private final int stripes = 64;
    private final ReentrantLock[] locks = new ReentrantLock[stripes];

    public PartitionLockManager() {
        for (int i = 0; i < stripes; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * Возвращает lock, соответствующий указанному ключу.
     *
     * <p>Один и тот же ключ всегда мапится на один и тот же lock,
     * но разные ключи могут делить один lock (hash-based partitioning).
     *
     * @param key ключ (например, orderId, symbol)
     * @return ReentrantLock для синхронизации
     */
    public ReentrantLock getLock(String key) {
        int index = Math.abs(key.hashCode() % stripes);
        return locks[index];
    }
}