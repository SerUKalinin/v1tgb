package com.tradingbot.domain.execution;

import com.tradingbot.domain.model.Trade;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ExecutionResult {
    private boolean success;
    private String orderId;
    private List<Trade> trades;
}