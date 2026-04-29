package com.tradingbot.application.service;

import com.tradingbot.application.service.order.OrderApplicationService;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ClientOrderIdTest {

    private final Pattern pattern = Pattern.compile("^[a-zA-Z0-9-_]{1,36}$");

    @Test
    void testGenerateClientOrderId() {
        OrderApplicationService service = new OrderApplicationService(null, null, null, null, null, null);
        UUID orderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        
        // 1. Детерминированность
        String id1 = invokeGenerateId(service, orderId);
        String id2 = invokeGenerateId(service, orderId);
        assertEquals(id1, id2, "ID must be deterministic");

        // 2. Формат (без дефисов)
        assertFalse(id1.contains("-"), "ID must not contain dashes");

        // 3. Валидность regex и длина
        assertTrue(pattern.matcher(id1).matches(), "ID must match regex ^[a-zA-Z0-9-_]{1,36}$");
        assertTrue(id1.length() <= 36, "ID length must not exceed 36");

        // 4. Префикс (если влезает)
        assertTrue(id1.startsWith("bot_"), "ID should start with bot_ if length allows");
    }

    private String invokeGenerateId(OrderApplicationService service, UUID orderId) {
        try {
            var method = OrderApplicationService.class.getDeclaredMethod("generateClientOrderId", UUID.class);
            method.setAccessible(true);
            return (String) method.invoke(service, orderId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
