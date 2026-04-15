package com.tradingbot.application.service;

import com.tradingbot.domain.risk.RiskStateStore;
import com.tradingbot.domain.risk.RiskStateStore;
import com.tradingbot.infrastructure.persistence.entity.RiskStateSnapshotEntity;
import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskStateSnapshotRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Recovers RiskState from database on startup and manages snapshots.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskStateRecoveryService {
    private final TradeRepository tradeRepository;
    private final RiskStateSnapshotRepository snapshotRepository;
    private final RiskStateStore riskStateStore;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverState() {
        log.info("[RISK-RECOVERY] Starting risk state recovery from database...");

        var latestSnapshot = snapshotRepository.findLatest();
        Instant recoveryStartTime = latestSnapshot
                .map(RiskStateSnapshotEntity::getTimestamp)
                .orElse(Instant.now().truncatedTo(ChronoUnit.DAYS));

        if (latestSnapshot.isPresent()) {
            var snap = latestSnapshot.get();
            log.info("[RISK-RECOVERY] Found snapshot from {}", snap.getTimestamp());
            riskStateStore.updateCustom(state -> state.toBuilder()
                    .balance(snap.getBalance())
                    .totalEquity(snap.getEquity())
                    .dailyPnl(snap.getDailyPnl())
                    .maxEquity(snap.getMaxEquity())
                    .processedEventIds(new HashSet<>(Arrays.asList(snap.getProcessedEventIds().split(","))))
                    .lastUpdateTimestamp(snap.getTimestamp())
                    .build());
        }
        List<TradeEntity> newTrades = tradeRepository.findAllByExecutedAtAfter(recoveryStartTime);

        BigDecimal additionalPnl = newTrades.stream()
                .map(TradeEntity::getRealizedPnl)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        log.info("[RISK-RECOVERY] Found {} new trades since {}. Additional PnL: {}", 
                newTrades.size(), recoveryStartTime, additionalPnl);

        riskStateStore.updateCustom(state -> state.toBuilder()
                .dailyPnl(state.getDailyPnl().add(additionalPnl))
                .lastUpdateTimestamp(Instant.now())
                .build());

        log.info("[RISK-RECOVERY] Recovery complete. Current Daily PnL: {}", 
                riskStateStore.getState().getDailyPnl());
    }

    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void saveSnapshot() {
        var state = riskStateStore.getState();
        log.debug("[RISK-SNAPSHOT] Saving current risk state snapshot...");

        RiskStateSnapshotEntity snapshot = RiskStateSnapshotEntity.builder()
                .timestamp(Instant.now())
                .balance(state.getBalance())
                .equity(state.getTotalEquity())
                .dailyPnl(state.getDailyPnl())
                .maxEquity(state.getMaxEquity())
                .processedEventIds(String.join(",", state.getProcessedEventIds()))
                .build();

        snapshotRepository.save(snapshot);
    }}
