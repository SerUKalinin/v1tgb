package com.tradingbot.common.util;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Утилита для генерации Client Order ID.
 *
 * <p>Используется для формирования идентификаторов ордеров,
 * совместимых с требованиями биржи (например Binance):</p>
 * <pre>
 * ^[a-zA-Z0-9-_]{1,36}$
 * </pre>
 *
 * <p>Обеспечивает:
 * <ul>
 *     <li>детерминированность ID</li>
 *     <li>валидность формата</li>
 *     <li>ограничение длины</li>
 * </ul>
 */
public class ClientOrderIdGenerator {

    private static final String PREFIX = "bot_";
    private static final Pattern VALID_PATTERN = Pattern.compile("^[a-zA-Z0-9-_]{1,36}$");

    /**
     * Генерирует Client Order ID на основе UUID.
     *
     * <p>Алгоритм:
     * <ul>
     *     <li>удаление дефисов из UUID</li>
     *     <li>добавление префикса</li>
     *     <li>обрезка до 36 символов при необходимости</li>
     * </ul>
     *
     * @param orderId UUID ордера
     * @return строковый идентификатор ордера
     * @throws IllegalArgumentException если orderId == null
     */
    public static String generate(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId cannot be null");
        }

        String hex = orderId.toString().replace("-", "");
        String result = PREFIX + hex;

        // если превышает лимит — убираем префикс и обрезаем hex
        if (result.length() > 36) {
            return hex.substring(0, 36);
        }

        return result;
    }

    /**
     * Проверяет соответствие ID требованиям биржи.
     *
     * @param id client order id
     * @return true если формат валиден
     */
    public static boolean validate(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        return VALID_PATTERN.matcher(id).matches();
    }
}