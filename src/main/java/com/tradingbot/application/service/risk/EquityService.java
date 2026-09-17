package com.tradingbot.application.service.risk;

import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.EquitySnapshot;
import com.tradingbot.domain.model.EquitySnapshotPort;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.model.Trade;
import com.tradingbot.domain.model.TradePort;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStatePort;
import com.tradingbot.tracing.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Сервис управления equity стратегии.
 *
 * <p>
 * Persistence скрыта за domain ports.
 *
 * <p>
 * Quote capital берётся только из canonical RiskState.
 *
 * <p>
 * Контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EquityService {

    private final EquitySnapshotPort equitySnapshotPort;
    private final PositionService positionService;
    private final TradePort tradePort;
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
        if (event == null) {
            throw new IllegalArgumentException(
                    "event cannot be null"
            );
        }

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
     * <pre>
     * equity = balance + unrealizedPnL
     * </pre>
     */
    public void createSnapshot(
            String strategyId,
            String symbol,
            BigDecimal currentPrice
    ) {
        RiskState riskState =
                riskStatePort.get();

        if (riskState == null) {
            throw new IllegalStateException(
                    "RiskState cannot be null"
            );
        }

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

        EquitySnapshot snapshot =
                EquitySnapshot.builder()
                        .strategyId(strategyId)
                        .timestamp(Instant.now())
                        .balance(balance)
                        .unrealizedPnl(unrealizedPnl)
                        .equity(equity)
                        .build();

        equitySnapshotPort.save(snapshot);

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
     * Рассчитывает суммарный исторический PnL
     * на основе trade ledger.
     *
     * <p>
     * Сохраняем существующую семантику метода без изменения
     * финансовой логики: BUY уменьшает значение,
     * SELL увеличивает.
     */
    public BigDecimal calculateTotalRealizedPnL() {

        List<Trade> trades =
                tradePort.findAll();

        return trades.stream()
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