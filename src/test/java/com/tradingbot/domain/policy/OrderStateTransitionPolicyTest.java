package com.tradingbot.domain.policy;

import com.tradingbot.common.enums.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class OrderStateTransitionPolicyTest {

    @Test
    void testForbiddenTransitionToUnknown() {
        // Запрещенные переходы в UNKNOWN
        assertThrows(IllegalStateException.class, () -> 
            OrderStateTransitionPolicy.requestTransition(OrderStatus.NEW, OrderStatus.UNKNOWN));
        
        assertThrows(IllegalStateException.class, () -> 
            OrderStateTransitionPolicy.requestTransition(OrderStatus.PENDING_EXECUTION, OrderStatus.UNKNOWN));
        
        assertThrows(IllegalStateException.class, () -> 
            OrderStateTransitionPolicy.requestTransition(OrderStatus.FILLED, OrderStatus.UNKNOWN));
    }

    @Test
    void testAllowedTransitionToUnknown() {
        // Разрешенный переход в UNKNOWN только из EXECUTING
        assertDoesNotThrow(() -> 
            OrderStateTransitionPolicy.requestTransition(OrderStatus.EXECUTING, OrderStatus.UNKNOWN));
    }

    @Test
    void testTerminalStatesAreLocked() {
        OrderStatus[] terminalStates = {OrderStatus.FILLED, OrderStatus.REJECTED, OrderStatus.CANCELED};
        
        for (OrderStatus terminal : terminalStates) {
            for (OrderStatus target : OrderStatus.values()) {
                if (terminal != target) {
                    assertFalse(OrderStateTransitionPolicy.canTransition(terminal, target), 
                        "Terminal state " + terminal + " must not transition to " + target);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"NEW", "PENDING_EXECUTION", "EXECUTING", "UNKNOWN"})
    void testSelfTransitionIsAlwaysAllowed(OrderStatus status) {
        assertTrue(OrderStateTransitionPolicy.canTransition(status, status));
        assertDoesNotThrow(() -> OrderStateTransitionPolicy.requestTransition(status, status));
    }
}
