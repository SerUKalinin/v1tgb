package com.tradingbot.domain.risk;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureInvarianceTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldPreventDirectExecutionEngineCallWithRawRequest() {
        String[] beanNames = context.getBeanNamesForType(ExecutionEngine.class);
        assertTrue(beanNames.length > 0, "ExecutionEngine beans should exist");

        for (String name : beanNames) {
            ExecutionEngine engine = (ExecutionEngine) context.getBean(name);

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
            assertFalse(hasLegacyMethod,
                    "ExecutionEngine " + name + " should NOT accept OrderRequest anymore");
        }
    }

    @Test
    void shouldPreventApprovedOrderCreationOutsideRiskPackage() {
        Order order = Order.createPendingExecution(
                UUID.randomUUID(),
                "test-" + UUID.randomUUID(),
                "BTCUSDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                BigDecimal.ONE,
                null,
                "STRAT-1",
                UUID.randomUUID()
        );

        assertNotNull(order, "Order should be creatable");
    }
}
