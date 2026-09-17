package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.model.EquitySnapshot;
import com.tradingbot.domain.model.EquitySnapshotPort;
import com.tradingbot.infrastructure.persistence.entity.EquitySnapshotEntity;
import com.tradingbot.infrastructure.persistence.repository.EquitySnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class JpaEquitySnapshotAdapter implements EquitySnapshotPort {

    private final EquitySnapshotRepository repository;

    @Override
    @Transactional
    public void save(EquitySnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "snapshot cannot be null"
            );
        }

        EquitySnapshotEntity entity =
                EquitySnapshotEntity.builder()
                        .strategyId(snapshot.getStrategyId())
                        .timestamp(snapshot.getTimestamp())
                        .balance(snapshot.getBalance())
                        .unrealizedPnl(snapshot.getUnrealizedPnl())
                        .equity(snapshot.getEquity())
                        .build();

        repository.save(entity);
    }
}