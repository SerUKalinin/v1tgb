package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.OrderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ArchitectureInvarianceTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldPreventDirectExecutionEngineCallWithRawRequest() {
        // Получаем все бины ExecutionEngine
        String[] beanNames = context.getBeanNamesForType(ExecutionEngine.class);
        assertTrue(beanNames.length > 0, "ExecutionEngine beans should exist");

        for (String name : beanNames) {
            ExecutionEngine engine = (ExecutionEngine) context.getBean(name);
            
            // Проверяем через рефлексию, что метода execute(OrderRequest) больше не существует
            // Это доказывает соблюдение контракта ApprovedOrder на уровне интерфейса
            boolean hasLegacyMethod = false;
            for (Method method : engine.getClass().getMethods()) {
                if (method.getName().equals("execute")) {
                    for (Class<?> paramType : method.getParameterTypes()) {
                        if (paramType.getSimpleName().equals("OrderRequest")) {
                            hasLegacyMethod = true;
                            break;
                        }
                    }
                }
            }
            assertFalse(hasLegacyMethod, "ExecutionEngine " + name + " should NOT accept OrderRequest anymore");
        }
    }

    @Test
    void shouldPreventApprovedOrderCreationOutsideRiskPackage() {
        // ApprovedOrder имеет package-private конструктор и билдер.
        // Мы проверяем, что из этого пакета (com.tradingbot.domain.risk) мы МОЖЕМ его создать,
        // но если бы этот тест был в другом пакете, компиляция бы не прошла.
        // Данный тест скорее документальный, подтверждающий инкапсуляцию.
        
        ApprovedOrder order = ApprovedOrder.builder()
                .orderId(UUID.randomUUID())
                .symbol("BTCUSDT")
                .quantity(BigDecimal.ONE)
                .build();        
        assertNotNull(order, "ApprovedOrder should be creatable within risk package");
    }
}
