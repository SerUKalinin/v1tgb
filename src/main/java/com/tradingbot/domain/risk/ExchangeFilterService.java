package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.OrderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Service for fail-fast validation and per-symbol concurrency control.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeFilterService {
    private final List<RiskRule> rules;
    private final RiskStateStore stateStore;
    private final Map<String, Lock> symbolLocks = new ConcurrentHashMap<>();

    public RiskDecision filter(OrderRequest request) {
        Lock lock = symbolLocks.computeIfAbsent(request.getSymbol(), k -> new ReentrantLock());
        
        if (!lock.tryLock()) {
            return RiskDecision.reject("Concurrent order processing for symbol: " + request.getSymbol());
        }

        try {
            RiskState currentState = stateStore.getState();
            for (RiskRule rule : rules) {
                RiskDecision decision = rule.evaluate(request, currentState);
                if (!decision.isApproved()) {
                    log.warn("Risk rule rejected order: {}", decision.getReason());
                    return decision;
                }
            }
            return RiskDecision.approve(request.getQuantity());
        } finally {            lock.unlock();
        }
    }
}
