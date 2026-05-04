package com.tradingbot.common.util;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Утилита для генерации компактных и валидных Client Order ID.
 * Соответствует требованиям Binance: ^[a-zA-Z0-9-_]{1,36}$
 */
public class ClientOrderIdGenerator {

    private static final String PREFIX = "bot_";
    private static final Pattern VALID_PATTERN = Pattern.compile("^[a-zA-Z0-9-_]{1,36}$");

    /**
     * Генерирует детерминированный компактный ID на основе UUID.
     * Использует hex-представление без дефисов.
     */
    public static String generate(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId cannot be null");
        }
        
        String hex = orderId.toString().replace("-", "");
        String result = PREFIX + hex;
        
        // Если превышает 36 символов, обрезаем префикс или часть hex
        if (result.length() > 36) {
            return hex.substring(0, 36);
        }
        
        return result;
    }

    /**
     * Проверяет ID на соответствие формату биржи.
     */
    public static boolean validate(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        return VALID_PATTERN.matcher(id).matches();
    }
}
