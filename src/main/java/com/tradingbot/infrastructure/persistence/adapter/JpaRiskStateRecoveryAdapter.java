package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStateRecoveryPort;
import com.tradingbot.infrastructure.persistence.mapper.RiskStateMapper;
import com.tradingbot.infrastructure.persistence.repository.RiskEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskReservationLogRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskSnapshotRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * JPA adapter для RiskStateRecoveryPort.
 *
 * Все persistence dependencies остаются исключительно
 * внутри infrastructure.
 */
@Component
@RequiredArgsConstructor
public class JpaRiskStateRecoveryAdapter
        implements RiskStateRecoveryPort {

    private final RiskEventRepository eventRepository;

    private final RiskSnapshotRepository snapshotRepository;

    private final RiskReservationLogRepository
            reservationLogRepository;

    private final RiskStateRepository riskStateRepository;

    private final RiskStateMapper riskStateMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findLatestSnapshotStateJson(
            String aggregateId
    ) {

        return snapshotRepository
                .findFirstByAggregateIdOrderByLastVersionDesc(
                        aggregateId
                )
                .map(entity -> entity.getStateJson());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RiskState> findPersistedState(
            String aggregateId
    ) {

        return riskStateRepository
                .findById(aggregateId)
                .map(riskStateMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RiskEventRecord> findEventsAfter(
            String aggregateId,
            Long version
    ) {

        return eventRepository
                .findByAggregateIdAndVersionGreaterThanOrderByVersionAsc(
                        aggregateId,
                        version
                )
                .stream()
                .map(entity ->
                        new RiskEventRecord(
                                entity.getEventType(),
                                entity.getPayload()
                        )
                )
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RiskReservationRecord> findAllReservations() {

        return reservationLogRepository
                .findAllByOrderBySequenceIdAsc()
                .stream()
                .map(entity ->
                        new RiskReservationRecord(
                                entity.getOrderId(),
                                entity.getEventType(),
                                entity.getAmount()
                        )
                )
                .toList();
    }
}