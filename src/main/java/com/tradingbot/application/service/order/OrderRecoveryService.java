package com.tradingbot.application.service.order;

import com.tradingbot.domain.exchange.ExecutionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Сервис восстановления состояния торговой системы после перезапуска.
 *
 * <p>Отвечает за первичную синхронизацию состояния позиций и балансов
 * между локальной системой и биржевым источником данных.</p>
 *
 * <p>Используется на этапе cold start / recovery для обеспечения
 * консистентности состояния после рестарта приложения.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderRecoveryService {

    private final ExecutionPort executionPort;
    private final OrderManagementService oms;

    /**
     * Выполняет восстановление позиций на основе актуальных данных с биржи.
     *
     * <p>Алгоритм:
     * <ul>
     *     <li>получение балансов с биржи</li>
     *     <li>сопоставление с локальными символами</li>
     *     <li>логирование расхождений</li>
     * </ul>
     *
     * <p>Фактическая reconciliation-логика (сверка и корректировка PositionService)
     * должна выполняться на уровне доменного reconciliation pipeline.</p>
     *
     * @param symbols список торговых символов для восстановления
     */
    public void recoverPositions(List<String> symbols) {
        log.info("[RECOVERY] Starting position recovery for symbols: {}", symbols);

        try {
            Map<String, BigDecimal> balances = executionPort.getBalances();

            for (String symbol : symbols) {
                // Упрощенно: извлекаем базовый актив (BTC из BTCUSDT)
                String baseAsset = symbol.replace("USDT", "");

                if (balances.containsKey(baseAsset)) {
                    BigDecimal total = balances.get(baseAsset);
                    log.info("[RECOVERY] Found balance for {}: total={}", baseAsset, total);

                    // NOTE: здесь должна быть доменная reconciliation логика
                }
            }
        } catch (Exception e) {
            log.error("[RECOVERY] Failed to recover positions", e);
        }
    }
}