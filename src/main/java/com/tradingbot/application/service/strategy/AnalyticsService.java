package com.tradingbot.application.service.strategy;

import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Сервис аналитики торговой системы.
 *
 * <p>Отвечает за расчёт агрегированных метрик производительности стратегии
 * на основе истории совершённых сделок.</p>
 *
 * <p>Основные метрики:
 * <ul>
 *     <li>winrate (процент прибыльных сделок)</li>
 *     <li>totalTrades (общее количество сделок)</li>
 *     <li>pnlPercent (суммарный PnL)</li>
 *     <li>profitFactor (отношение прибыли к убыткам)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    /**
     * Репозиторий сделок.
     */
    private final TradeRepository tradeRepository;

    /**
     * Рассчитывает глобальную статистику по всем сделкам.
     *
     * <p>Метод выполняет агрегацию всей истории сделок без фильтрации.</p>
     *
     * @return карта со следующими метриками:
     * <ul>
     *     <li>winrate — процент прибыльных сделок</li>
     *     <li>totalTrades — общее число сделок</li>
     *     <li>pnlPercent — суммарный PnL</li>
     *     <li>profitFactor — коэффициент прибыль/убыток</li>
     * </ul>
     */
    public Map<String, Object> getGlobalStats() {
        List<TradeEntity> allTrades = tradeRepository.findAll();

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
                .filter(t -> t.getRealizedPnl() != null
                        && t.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
                .count();

        double winrate = (double) winningTrades / totalTrades * 100;

        BigDecimal totalPnl = allTrades.stream()
                .map(t -> t.getRealizedPnl() != null ? t.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossProfit = allTrades.stream()
                .map(t -> t.getRealizedPnl() != null ? t.getRealizedPnl() : BigDecimal.ZERO)
                .filter(pnl -> pnl.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossLoss = allTrades.stream()
                .map(t -> t.getRealizedPnl() != null ? t.getRealizedPnl() : BigDecimal.ZERO)
                .filter(pnl -> pnl.compareTo(BigDecimal.ZERO) < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs();

        double profitFactor = grossLoss.compareTo(BigDecimal.ZERO) > 0
                ? grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP).doubleValue()
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
     * @param stats агрегированные метрики, полученные из {@link #getGlobalStats()}
     * @return форматированная строка для отображения пользователю
     */
    public String formatStatsMessage(Map<String, Object> stats) {
        return String.format(
                "📊 *Статистика системы*\n\n" +
                        "📈 Winrate: `%.1f%%`\n" +
                        "🔄 Всего сделок: `%d`\n" +
                        "💰 Общий PnL: `%+.2f` (abs)\n" +
                        "🏆 Profit Factor: `%.2f`\n\n" +
                        "_Данные рассчитаны на основе истории торгов_",
                stats.get("winrate"),
                stats.get("totalTrades"),
                stats.get("pnlPercent"),
                stats.get("profitFactor")
        );
    }
}