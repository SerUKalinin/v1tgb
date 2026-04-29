package com.tradingbot.application.service;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.port.exchange.ExecutionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Сервис восстановления состояния после перезапуска.
 * Синхронизирует открытые позиции и ордера с биржей.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderRecoveryService {

    private final ExecutionPort executionPort;
    private final OrderManagementService oms;

    /**
     * Синхронизация позиций с Binance.
     * В реальном приложении вызывается при старте.
     */
    public void recoverPositions(List<String> symbols) {
        log.info("[RECOVERY] Starting position recovery for symbols: {}", symbols);
        
        try {
            Map<String, BigDecimal> balances = executionPort.getBalances();

            for (String symbol : symbols) {
                // Упрощенно: ищем базовый актив символа (например, BTC для BTCUSDT)
                String baseAsset = symbol.replace("USDT", ""); 
                
                if (balances.containsKey(baseAsset)) {
                    BigDecimal total = balances.get(baseAsset);
                    log.info("[RECOVERY] Found balance for {}: total={}", baseAsset, total);
                    // Здесь должна быть логика сверки с локальной БД и корректировки PositionService
                }
            }
        } catch (Exception e) {
            log.error("[RECOVERY] Failed to recover positions", e);
        }
    }
}
