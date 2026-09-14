package com.tradingbot.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Централизованная утилита для выполнения математических операций с денежными значениями.
 *
 * <p>Обеспечивает строгую нормализацию всех операций с использованием фиксированной
 * точности и безопасного округления для предотвращения финансовых ошибок.</p>
 *
 * <p>Ключевые принципы:
 * <ul>
 *     <li>фиксированная scale = 18</li>
 *     <li>RoundingMode.DOWN для предотвращения завышения значений</li>
 *     <li>унификация всех арифметических операций</li>
 * </ul>
 */
public class MoneyMath {

    public static final int SCALE = 18;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.DOWN;

    public static final BigDecimal ZERO = scale(BigDecimal.ZERO);

    /**
     * Приводит значение к единому масштабу.
     *
     * @param value входное значение
     * @return нормализованное значение
     */
    public static BigDecimal scale(BigDecimal value) {
        if (value == null) return ZERO;
        return value.setScale(SCALE, ROUNDING_MODE);
    }

    /**
     * Сложение двух значений с нормализацией.
     */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return scale(scale(a).add(scale(b)));
    }

    /**
     * Вычитание двух значений с нормализацией.
     */
    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return scale(scale(a).subtract(scale(b)));
    }

    /**
     * Умножение двух значений с нормализацией.
     */
    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return scale(scale(a).multiply(scale(b)));
    }

    /**
     * Деление двух значений с защитой от деления на ноль.
     *
     * @return ZERO если делитель равен 0 или null
     */
    public static BigDecimal divide(BigDecimal a, BigDecimal b) {
        if (b == null || b.compareTo(BigDecimal.ZERO) == 0) {
            return ZERO;
        }
        return scale(a).divide(scale(b), SCALE, ROUNDING_MODE);
    }

    /**
     * Проверка: a > b
     */
    public static boolean isGreater(BigDecimal a, BigDecimal b) {
        return scale(a).compareTo(scale(b)) > 0;
    }

    /**
     * Проверка: a >= b
     */
    public static boolean isGreaterOrEqual(BigDecimal a, BigDecimal b) {
        return scale(a).compareTo(scale(b)) >= 0;
    }

    /**
     * Проверка: a < b
     */
    public static boolean isLess(BigDecimal a, BigDecimal b) {
        return scale(a).compareTo(scale(b)) < 0;
    }

    /**
     * Проверка на нулевое значение.
     */
    public static boolean isZero(BigDecimal a) {
        return a == null || scale(a).compareTo(ZERO) == 0;
    }
}