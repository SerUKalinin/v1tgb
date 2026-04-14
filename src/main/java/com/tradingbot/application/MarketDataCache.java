package com.tradingbot.application;

import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class MarketDataCache {
    private final Map<String, Deque<Candle>> cache = new ConcurrentHashMap<>();
    private final Map<String, Lock> locks = new ConcurrentHashMap<>();
    private static final int MAX_SIZE = 100;

    public void updateOrAdd(String symbol, Candle newCandle) {
        Lock lock = locks.computeIfAbsent(symbol, k -> new ReentrantLock());
        lock.lock();
        try {
            Deque<Candle> window = cache.computeIfAbsent(symbol, k -> new ArrayDeque<>(MAX_SIZE));
            
            if (!window.isEmpty()) {
                Candle last = window.peekLast();
                if (last.getOpenTime().equals(newCandle.getOpenTime())) {
                    window.removeLast();
                } else if (window.size() >= MAX_SIZE) {
                    window.pollFirst();
                }
            }
            window.addLast(newCandle);
        } finally {
            lock.unlock();
        }
    }

    public void addAll(String symbol, List<Candle> candles) {
        Lock lock = locks.computeIfAbsent(symbol, k -> new ReentrantLock());
        lock.lock();
        try {
            Deque<Candle> window = new ArrayDeque<>(MAX_SIZE);
            int start = Math.max(0, candles.size() - MAX_SIZE);
            for (int i = start; i < candles.size(); i++) {
                window.addLast(candles.get(i));
            }
            cache.put(symbol, window);
        } finally {
            lock.unlock();
        }
    }

    public CandleWindow getWindow(String symbol) {
        Deque<Candle> window = cache.get(symbol);
        if (window == null) {
            return new CandleWindow(symbol, List.of());
        }
        
        Lock lock = locks.get(symbol);
        if (lock != null) {
            lock.lock();
            try {
                return new CandleWindow(symbol, List.copyOf(window));
            } finally {
                lock.unlock();
            }
        }
        return new CandleWindow(symbol, List.of());
    }
}
