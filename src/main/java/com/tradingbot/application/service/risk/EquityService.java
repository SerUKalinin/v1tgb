package com.tradingbot.application.service.risk;

import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Position;
import com.tradingbot.infrastructure.persistence.entity.EquitySnapshotEntity;
import com.tradingbot.infrastructure.persistence.repository.EquitySnapshotRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import com.tradingbot.tracing.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис управления equity (капиталом) стратегии.
 *
 * <p>Отвечает за формирование и хранение снимков состояния капитала (equity snapshots),
 * включая реализованный и нереализованный PnL на основе позиций и сделок.</p>
 *
 * <p>Используется как часть risk/subsystem слоя для мониторинга финансового состояния
 * торговых стратегий.</p>
 *
 * <h2>Основные функции:</h2>
 * <ul>
 *     <li>Обработка события создания сделки (TradeCreatedEvent)</li>
 *     <li>Поддержание баланса стратегии (in-memory cache)</li>
 *     <li>Расчет unrealized PnL на основе открытых позиций</li>
 *     <li>Формирование snapshot-ов equity</li>
 *     <li>Расчет общего realized PnL</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EquityService {

    private final EquitySnapshotRepository equityRepository;
    private final PositionService positionService;
    private final TradeRepository tradeRepository;

    /**
     * In-memory баланс стратегий.
     * Используется как быстрый кэш базового капитала между snapshot-ами.
     */
    private final Map<String, BigDecimal> strategyBalances = new ConcurrentHashMap<>();

    /**
     * Начальный баланс стратегии по умолчанию.
     */
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000");

    /**
     * Обрабатывает событие создания сделки и инициирует обновление equity snapshot.
     *
     * <p>Создаёт ExecutionContext для трассировки и обновляет внутренний баланс стратегии,
     * после чего формирует snapshot текущего состояния equity.</p>
     *
     * @param event событие создания сделки
     */
    @Transactional
    public void onTradeCreated(TradeCreatedEvent event) {
        ExecutionContext context = ExecutionContext.of(
                event.getIdentity(),
                event.getAttempt(),
                event.getBusiness()
        );

        log.info("[EQUITY] Updating balance for strategy {} after trade {}",
                event.getStrategyId(),
                event.getTradeId());

        strategyBalances.putIfAbsent(event.getStrategyId(), INITIAL_BALANCE);

        createSnapshot(event.getStrategyId(), event.getSymbol(), event.getPrice());
    }

    /**
     * Создаёт snapshot состояния equity для указанной стратегии.
     *
     * <p>Equity рассчитывается как:
     * <pre>
     * equity = balance + unrealizedPnL
     * </pre>
     * где unrealizedPnL вычисляется на основе текущей позиции.</p>
     *
     * @param strategyId идентификатор стратегии
     * @param symbol торговый символ
     * @param currentPrice текущая рыночная цена
     */
    public void createSnapshot(String strategyId, String symbol, BigDecimal currentPrice) {
        BigDecimal balance = strategyBalances.getOrDefault(strategyId, INITIAL_BALANCE);

        Position position = positionService.getPosition(symbol, strategyId);

        BigDecimal unrealizedPnl = BigDecimal.ZERO;

        if (position != null && position.getNetQuantity().signum() != 0) {
            unrealizedPnl = currentPrice.subtract(position.getAvgEntryPrice())
                    .multiply(position.getNetQuantity());
        }

        BigDecimal equity = balance.add(unrealizedPnl);

        EquitySnapshotEntity snapshot = EquitySnapshotEntity.builder()
                .strategyId(strategyId)
                .timestamp(Instant.now())
                .balance(balance)
                .unrealizedPnl(unrealizedPnl)
                .equity(equity)
                .build();

        equityRepository.save(snapshot);

        log.info("[EQUITY] Snapshot saved for {}: Equity={}, Balance={}, UPnL={}",
                strategyId, equity, balance, unrealizedPnl);
    }

    /**
     * Рассчитывает суммарный реализованный PnL по всем сделкам.
     *
     * <p>Используется упрощённая модель расчёта на основе направления сделки.</p>
     *
     * @return суммарный realized PnL
     */
    public BigDecimal calculateTotalRealizedPnL() {
        return tradeRepository.findAll().stream()
                .map(t -> {
                    BigDecimal sign = t.getSide().name().equals("BUY")
                            ? BigDecimal.valueOf(-1)
                            : BigDecimal.valueOf(1);

                    return t.getPrice()
                            .multiply(t.getQuantity())
                            .multiply(sign);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }
}