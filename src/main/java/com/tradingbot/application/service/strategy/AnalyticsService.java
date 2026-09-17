package com.tradingbot.application.service.strategy;

import com.tradingbot.domain.model.Trade;
import com.tradingbot.domain.model.TradePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Application service аналитики торговой системы.
 *
 * <p>
 * Работает только с domain model {@link Trade}
 * и persistence boundary {@link TradePort}.
 *
 * <p>
 * Архитектурные контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final TradePort tradePort;

    /**
     * Рассчитывает глобальную статистику по всем сделкам.
     *
     * @return агрегированные торговые метрики
     */
    public Map<String, Object> getGlobalStats() {
        List<Trade> allTrades = tradePort.findAll();

        if (allTrades.isEmpty()) {
            return Map.of(
                    "winrate", 0.0,
                    "totalTrades", 0,
                    "pnlPercent", 0.0,
                    "profitFactor", 0.0
            );
        }

        long totalTrades = allTrades.size();

        long winningTrades = allTrades.stream()
                .filter(this::isWinningTrade)
                .count();

        double winrate =
                (double) winningTrades / totalTrades * 100.0;

        BigDecimal totalPnl = allTrades.stream()
                .map(this::realizedPnlOrZero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossProfit = allTrades.stream()
                .map(this::realizedPnlOrZero)
                .filter(pnl ->
                        pnl.compareTo(BigDecimal.ZERO) > 0
                )
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossLoss = allTrades.stream()
                .map(this::realizedPnlOrZero)
                .filter(pnl ->
                        pnl.compareTo(BigDecimal.ZERO) < 0
                )
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs();

        double profitFactor =
                grossLoss.compareTo(BigDecimal.ZERO) > 0
                        ? grossProfit
                          .divide(
                                  grossLoss,
                                  2,
                                  RoundingMode.HALF_UP
                          )
                          .doubleValue()
                        : grossProfit.doubleValue();

        return Map.of(
                "winrate", winrate,
                "totalTrades", (int) totalTrades,
                "pnlPercent", totalPnl.doubleValue(),
                "profitFactor", profitFactor
        );
    }

    /**
     * Формирует человекочитаемое сообщение со статистикой.
     *
     * @param stats агрегированные метрики
     * @return форматированная строка
     */
    public String formatStatsMessage(
            Map<String, Object> stats
    ) {
        return String.format(
                """
                📊 *Статистика системы*

                📈 Winrate: `%.1f%%`
                🔄 Всего сделок: `%d`
                💰 Общий PnL: `%+.2f` (abs)
                🏆 Profit Factor: `%.2f`

                _Данные рассчитаны на основе истории торгов_
                """,
                stats.get("winrate"),
                stats.get("totalTrades"),
                stats.get("pnlPercent"),
                stats.get("profitFactor")
        );
    }

    private boolean isWinningTrade(Trade trade) {
        return trade != null
                && trade.getRealizedPnl() != null
                && trade.getRealizedPnl()
                .compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal realizedPnlOrZero(Trade trade) {
        if (trade == null || trade.getRealizedPnl() == null) {
            return BigDecimal.ZERO;
        }

        return trade.getRealizedPnl();
    }
}