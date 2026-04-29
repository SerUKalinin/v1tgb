package com.tradingbot.common.util;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ClientOrderIdGeneratorTest {

    @Test
    void testMassGeneration() {
        int count = 1000;
        Set<String> generatedIds = new HashSet<>();

        for (int i = 0; i < count; i++) {
            UUID originalUuid = UUID.randomUUID();
            String clientOrderId = ClientOrderIdGenerator.generate(originalUuid);

            // 1. Валидация формата
            assertTrue(ClientOrderIdGenerator.validate(clientOrderId), 
                "Generated ID must be valid: " + clientOrderId);
            
            // 2. Проверка длины
            assertTrue(clientOrderId.length() <= 36, 
                "Length must be <= 36: " + clientOrderId.length());

            // 3. Проверка на отсутствие дефисов
            assertFalse(clientOrderId.contains("-"), 
                "ID must not contain dashes: " + clientOrderId);

            generatedIds.add(clientOrderId);
        }

        // 4. Проверка уникальности (детерминированность не означает коллизии для разных UUID)
        assertEquals(count, generatedIds.size(), "All 1000 IDs should be unique");
    }

    @Test
    void testDeterminism() {
        UUID id = UUID.randomUUID();
        String gen1 = ClientOrderIdGenerator.generate(id);
        String gen2 = ClientOrderIdGenerator.generate(id);
        assertEquals(gen1, gen2, "Generation must be deterministic");
    }
}
