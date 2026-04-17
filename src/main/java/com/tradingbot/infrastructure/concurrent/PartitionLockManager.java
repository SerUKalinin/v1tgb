package com.tradingbot.infrastructure.concurrent;

import org.springframework.stereotype.Component;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class PartitionLockManager {
    private final int stripes = 64;
    private final ReentrantLock[] locks = new ReentrantLock[stripes];

    public PartitionLockManager() {
        for (int i = 0; i < stripes; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public ReentrantLock getLock(String key) {
        int index = Math.abs(key.hashCode() % stripes);
        return locks[index];
    }
}
