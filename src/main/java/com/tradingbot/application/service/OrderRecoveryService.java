package com.tradingbot.application.service;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
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

    private final BinanceClient binanceClient;
    private final OrderManagementService oms;

    /**
     * Синхронизация позиций с Binance.
     * В реальном приложении вызывается при старте.
     */
    public void recoverPositions(List<String> symbols) {
        log.info("[RECOVERY] Starting position recovery for symbols: {}", symbols);
        
        try {
            // Получаем информацию об аккаунте (балансы)
            Map<String, Object> accountInfo = binanceClient.get("/api/v3/account", Map.of(), Map.class, true);
            List<Map<String, String>> balances = (List<Map<String, String>>) accountInfo.get("balances");

            for (String symbol : symbols) {
                // Упрощенно: ищем базовый актив символа (например, BTC для BTCUSDT)
                String baseAsset = symbol.replace("USDT", ""); 
                
                balances.stream()
                    .filter(b -> b.get("asset").equals(baseAsset))
                    .findFirst()
                    .ifPresent(balance -> {
                        BigDecimal free = new BigDecimal(balance.get("free"));
                        BigDecimal locked = new BigDecimal(balance.get("locked"));
                        BigDecimal total = free.add(locked);
                        
                        log.info("[RECOVERY] Found balance for {}: total={}", baseAsset, total);
                        // Здесь должна быть логика сверки с локальной БД и корректировки PositionService
                    });
            }
        } catch (Exception e) {
            log.error("[RECOVERY] Failed to recover positions", e);
        }
    }
}
