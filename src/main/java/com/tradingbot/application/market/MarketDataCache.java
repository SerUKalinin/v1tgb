package com.tradingbot.application.market;

import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Кэш рыночных данных для хранения истории свечей
 * по торговым символам.
 *
 * <p>Используется как in-memory структура для построения
 * CandleWindow, необходимых стратегиям и детекторам.
 *
 * <p>Для каждого символа хранится ограниченное количество
 * последних свечей.
 *
 * <p>Реализация потокобезопасна за счёт ConcurrentHashMap
 * и отдельного lock для каждого символа.
 */
@Slf4j
@Component
public class MarketDataCache {

    /**
     * Максимальное количество свечей в окне.
     */
    private static final int MAX_SIZE = 100;

    /**
     * Кэш свечей по символу.
     */
    private final Map<String, Deque<Candle>> cache =
            new ConcurrentHashMap<>();

    /**
     * Locks по торговому символу.
     */
    private final Map<String, Lock> locks =
            new ConcurrentHashMap<>();

    /**
     * Обновляет или добавляет свечу в кэш
     * для указанного символа.
     *
     * <p>Если candle с таким openTime уже существует,
     * она заменяется.
     *
     * <p>Если candle новая и находится после текущей последней,
     * она добавляется в конец.
     *
     * <p>При превышении MAX_SIZE удаляется самая старая свеча.
     *
     * @param symbol    торговый символ
     * @param newCandle новая свеча
     */
    public void updateOrAdd(
            String symbol,
            Candle newCandle
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException(
                    "symbol не должен быть пустым"
            );
        }

        if (newCandle == null) {
            throw new IllegalArgumentException(
                    "newCandle не должен быть null"
            );
        }

        Lock lock = locks.computeIfAbsent(
                symbol,
                key -> new ReentrantLock()
        );

        lock.lock();

        try {
            Deque<Candle> window =
                    cache.computeIfAbsent(
                            symbol,
                            key -> new ArrayDeque<>(MAX_SIZE)
                    );

            List<Candle> candles =
                    new ArrayList<>(window);

            boolean replaced = false;

            /*
             * Ищем candle с тем же openTime
             * по всему окну, а не только среди последней.
             *
             * Это важно, поскольку Binance refresh
             * возвращает перекрывающиеся свечи.
             */
            for (int i = 0; i < candles.size(); i++) {
                Candle existing = candles.get(i);

                if (existing.getOpenTime()
                        .equals(newCandle.getOpenTime())) {

                    candles.set(i, newCandle);
                    replaced = true;
                    break;
                }
            }

            if (!replaced) {

                /*
                 * Новая свеча должна быть новее
                 * текущей последней.
                 *
                 * Старые/запоздалые данные игнорируются.
                 */
                if (!candles.isEmpty()) {
                    Candle last =
                            candles.get(candles.size() - 1);

                    if (!newCandle.getOpenTime()
                            .isAfter(last.getOpenTime())) {

                        return;
                    }
                }

                if (candles.size() >= MAX_SIZE) {
                    candles.remove(0);
                }

                candles.add(newCandle);
            }

            window.clear();
            window.addAll(candles);

        } finally {
            lock.unlock();
        }
    }

    /**
     * Полностью заменяет кэш свечей
     * для указанного символа.
     *
     * <p>Сохраняются только последние MAX_SIZE свечей.
     *
     * @param symbol  торговый символ
     * @param candles список свечей
     */
    public void addAll(
            String symbol,
            List<Candle> candles
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException(
                    "symbol не должен быть пустым"
            );
        }

        if (candles == null) {
            throw new IllegalArgumentException(
                    "candles не должен быть null"
            );
        }

        Lock lock = locks.computeIfAbsent(
                symbol,
                key -> new ReentrantLock()
        );

        lock.lock();

        try {
            Deque<Candle> window =
                    new ArrayDeque<>(MAX_SIZE);

            int start =
                    Math.max(
                            0,
                            candles.size() - MAX_SIZE
                    );

            for (int i = start;
                 i < candles.size();
                 i++) {

                Candle candle =
                        candles.get(i);

                if (candle == null) {
                    continue;
                }

                if (!window.isEmpty()) {
                    Candle previous =
                            window.peekLast();

                    if (!candle.getOpenTime()
                            .isAfter(previous.getOpenTime())) {

                        throw new IllegalArgumentException(
                                "Свечи должны быть отсортированы "
                                        + "по возрастанию openTime: "
                                        + "previous="
                                        + previous.getOpenTime()
                                        + ", current="
                                        + candle.getOpenTime()
                        );
                    }
                }

                window.addLast(candle);
            }

            cache.put(
                    symbol,
                    window
            );

        } finally {
            lock.unlock();
        }
    }

    /**
     * Возвращает текущее окно свечей
     * для указанного символа.
     *
     * @param symbol торговый символ
     * @return snapshot окна свечей
     */
    public CandleWindow getWindow(
            String symbol
    ) {
        Deque<Candle> window =
                cache.get(symbol);

        if (window == null) {
            return new CandleWindow(
                    symbol,
                    List.of()
            );
        }

        Lock lock =
                locks.get(symbol);

        if (lock == null) {
            return new CandleWindow(
                    symbol,
                    List.of()
            );
        }

        lock.lock();

        try {
            return new CandleWindow(
                    symbol,
                    List.copyOf(window)
            );
        } finally {
            lock.unlock();
        }
    }
}