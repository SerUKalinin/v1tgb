package com.tradingbot.application.market;

import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Кэш рыночных данных для хранения истории свечей по символам.
 * <p>
 * Хранит до {@value #MAX_SIZE} последних свечей для каждого символа.
 * Потокобезопасен благодаря использованию {@link ConcurrentHashMap} и {@link ReentrantLock}.
 */
@Slf4j
@Component
public class MarketDataCache {

    private final Map<String, Deque<Candle>> cache = new ConcurrentHashMap<>();
    private final Map<String, Lock> locks = new ConcurrentHashMap<>();
    private static final int MAX_SIZE = 100;

    /**
     * Обновляет или добавляет свечу в кэш для указанного символа.
     * <p>
     * Если свеча с таким же временем открытия уже существует, она заменяется.
     * При превышении максимального размера удаляется самая старая свеча.
     *
     * @param symbol    торговый символ
     * @param newCandle новая свеча
     */
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

    /**
     * Заменяет кэш для указанного символа списком свечей.
     * <p>
     * Сохраняются только последние {@value #MAX_SIZE} свечей.
     *
     * @param symbol  торговый символ
     * @param candles список свечей
     */
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

    /**
     * Возвращает окно свечей для указанного символа.
     *
     * @param symbol торговый символ
     * @return окно свечей (может быть пустым)
     */
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