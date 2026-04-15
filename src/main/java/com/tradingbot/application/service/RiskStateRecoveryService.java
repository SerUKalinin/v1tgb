package com.tradingbot.application.service;

import com.tradingbot.domain.risk.RiskStateStore;
import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Recovers RiskState from database on startup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskStateRecoveryService {
    private final TradeRepository tradeRepository;
    private final RiskStateStore riskStateStore;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverState() {
        log.info("[RISK-RECOVERY] Starting risk state recovery from database...");

        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        List<TradeEntity> todaysTrades = tradeRepository.findAllByExecutedAtAfter(startOfDay);

        BigDecimal dailyPnl = todaysTrades.stream()
                .map(TradeEntity::getRealizedPnl)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        log.info("[RISK-RECOVERY] Found {} trades for today. Accumulated PnL: {}", todaysTrades.size(), dailyPnl);

        riskStateStore.updateCustom(state -> state.toBuilder()
                .dailyPnl(dailyPnl)
                .lastUpdateTimestamp(Instant.now())
                .build());

        log.info("[RISK-RECOVERY] Recovery complete. Current Daily PnL: {}", 
                riskStateStore.getState().getDailyPnl());
    }
}
