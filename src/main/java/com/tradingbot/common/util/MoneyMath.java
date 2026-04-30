package com.tradingbot.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Централизованная утилита для математических операций с деньгами и объемами.
 * Использует SCALE=18 и RoundingMode.DOWN для предотвращения завышения балансов.
 */
public class MoneyMath {
    public static final int SCALE = 18;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.DOWN;
    public static final BigDecimal ZERO = scale(BigDecimal.ZERO);

    public static BigDecimal scale(BigDecimal value) {
        if (value == null) return ZERO;
        return value.setScale(SCALE, ROUNDING_MODE);
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return scale(scale(a).add(scale(b)));
    }

    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return scale(scale(a).subtract(scale(b)));
    }

    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return scale(scale(a).multiply(scale(b)));
    }

    public static BigDecimal divide(BigDecimal a, BigDecimal b) {
        if (b == null || b.compareTo(BigDecimal.ZERO) == 0) {
            return ZERO;
        }
        return scale(a).divide(scale(b), SCALE, ROUNDING_MODE);
    }

    public static boolean isGreater(BigDecimal a, BigDecimal b) {
        return scale(a).compareTo(scale(b)) > 0;
    }

    public static boolean isGreaterOrEqual(BigDecimal a, BigDecimal b) {
        return scale(a).compareTo(scale(b)) >= 0;
    }

    public static boolean isLess(BigDecimal a, BigDecimal b) {
        return scale(a).compareTo(scale(b)) < 0;
    }

    public static boolean isZero(BigDecimal a) {
        return a == null || scale(a).compareTo(ZERO) == 0;
    }
}
