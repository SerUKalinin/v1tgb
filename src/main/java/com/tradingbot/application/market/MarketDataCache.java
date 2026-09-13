package com.tradingbot.application.market;

import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Кэш рыночных данных для хранения истории свечей по торговым символам.
 * <p>
 * Используется как in-memory структура для построения окон свечей (CandleWindow),
 * необходимых для стратегий и детекции событий.
 * <p>
 * Для каждого символа хранится ограниченное количество последних свечей (до {@value #MAX_SIZE}).
 * Реализация потокобезопасна за счёт {@link ConcurrentHashMap} и {@link ReentrantLock}.
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
     * Если свеча с тем же {@code openTime} уже существует, она заменяется.
     * При превышении лимита {@value #MAX_SIZE} удаляется самая старая свеча.
     *
     * @param symbol торговый символ (например BTCUSDT)
     * @param newCandle новая свеча для добавления или обновления
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
     * Полностью заменяет кэш свечей для указанного символа.
     * <p>
     * В кэше сохраняются только последние {@value #MAX_SIZE} свечей.
     *
     * @param symbol торговый символ
     * @param candles список свечей (история)
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
     * Возвращает текущее окно свечей для указанного символа.
     * <p>
     * Используется стратегиями и детекторами для анализа последнего состояния рынка.
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