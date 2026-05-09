package com.tradingbot.tracing;

import com.tradingbot.common.enums.OrderStatus;

public final class ExecutionStateMapper {

    private ExecutionStateMapper() {
    }

    public static String toContractState(OrderStatus status) {
        if (status == null) {
            return "PENDING";
        }
        return switch (status) {
            case PENDING_EXECUTION -> "PENDING";
            case EXECUTING -> "EXECUTING";
            case UNKNOWN -> "UNKNOWN";
            case FILLED -> "FILLED";
            case REJECTED -> "REJECTED";
            case CANCELED -> "CANCELLED";
            default -> "PENDING";
        };
    }
}
