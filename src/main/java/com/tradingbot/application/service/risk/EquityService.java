package com.tradingbot.application.service.risk;

import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStatePort;
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

/**
 * Сервис управления equity (капиталом) стратегии.
 *
 * <p>
 * Формирует equity snapshots на основе canonical RiskState
 * и текущей Position projection.
 * </p>
 *
 * <p>
 * Финансовый баланс НЕ хранится в памяти этого сервиса.
 * Источником истины для quote capital является RiskStatePort.
 * </p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EquityService {

    private final EquitySnapshotRepository equityRepository;
    private final PositionService positionService;
    private final TradeRepository tradeRepository;
    private final RiskStatePort riskStatePort;

    /**
     * Обрабатывает событие создания сделки и формирует
     * актуальный equity snapshot.
     *
     * @param event событие создания сделки
     */
    @Transactional
    public void onTradeCreated(
            TradeCreatedEvent event
    ) {
        ExecutionContext context =
                ExecutionContext.of(
                        event.getIdentity(),
                        event.getAttempt(),
                        event.getBusiness()
                );

        log.info(
                "[EQUITY] Updating equity for strategy {} after trade {}",
                event.getStrategyId(),
                event.getTradeId()
        );

        createSnapshot(
                event.getStrategyId(),
                event.getSymbol(),
                event.getPrice()
        );
    }

    /**
     * Создаёт snapshot состояния equity.
     *
     * <p>
     * Balance берётся исключительно из canonical RiskState.
     * Никакого in-memory initial balance здесь нет.
     * </p>
     *
     * <pre>
     * equity = balance + unrealizedPnL
     * </pre>
     *
     * @param strategyId идентификатор стратегии
     * @param symbol торговый символ
     * @param currentPrice текущая рыночная цена
     */
    public void createSnapshot(
            String strategyId,
            String symbol,
            BigDecimal currentPrice
    ) {
        RiskState riskState =
                riskStatePort.get();

        BigDecimal balance =
                riskState.getBalance();

        if (balance == null) {
            throw new IllegalStateException(
                    "RiskState balance cannot be null"
            );
        }

        Position position =
                positionService.getPosition(
                        symbol,
                        strategyId
                );

        BigDecimal unrealizedPnl =
                BigDecimal.ZERO;

        if (position != null
                && position.getNetQuantity() != null
                && position.getNetQuantity().signum() != 0) {

            if (position.getAvgEntryPrice() == null) {
                throw new IllegalStateException(
                        "Open position has null avgEntryPrice: "
                                + symbol
                                + ":"
                                + strategyId
                );
            }

            if (currentPrice == null) {
                throw new IllegalStateException(
                        "Current price cannot be null for equity snapshot: "
                                + symbol
                );
            }

            unrealizedPnl =
                    currentPrice
                            .subtract(
                                    position.getAvgEntryPrice()
                            )
                            .multiply(
                                    position.getNetQuantity()
                            );
        }

        BigDecimal equity =
                balance.add(
                        unrealizedPnl
                );

        EquitySnapshotEntity snapshot =
                EquitySnapshotEntity.builder()
                        .strategyId(strategyId)
                        .timestamp(Instant.now())
                        .balance(balance)
                        .unrealizedPnl(unrealizedPnl)
                        .equity(equity)
                        .build();

        equityRepository.save(snapshot);

        log.info(
                "[EQUITY] Snapshot saved for {}: Equity={}, Balance={}, UPnL={}, RiskVersion={}",
                strategyId,
                equity,
                balance,
                unrealizedPnl,
                riskState.getVersion()
        );
    }

    /**
     * Рассчитывает суммарный реализованный PnL
     * по истории сделок.
     *
     * <p>
     * Это отдельная историческая метрика и не является
     * источником текущего cash balance.
     * </p>
     *
     * @return суммарный realized PnL
     */
    public BigDecimal calculateTotalRealizedPnL() {
        return tradeRepository.findAll().stream()
                .map(trade -> {
                    BigDecimal sign =
                            trade.getSide().name().equals("BUY")
                                    ? BigDecimal.valueOf(-1)
                                    : BigDecimal.ONE;

                    return trade.getPrice()
                            .multiply(trade.getQuantity())
                            .multiply(sign);
                })
                .reduce(
                        BigDecimal.ZERO,
                        BigDecimal::add
                )
                .setScale(
                        2,
                        RoundingMode.HALF_UP
                );
    }
}