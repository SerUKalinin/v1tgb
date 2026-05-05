package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.risk.RiskReservationEventType;
import com.tradingbot.domain.risk.RiskReservationLog;
import com.tradingbot.domain.risk.RiskReservationLogPort;
import com.tradingbot.infrastructure.persistence.entity.RiskReservationLogEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskReservationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RiskReservationLogAdapter implements RiskReservationLogPort {

    private final RiskReservationLogRepository repository;

    @Override
    public void append(RiskReservationLog log) {
        RiskReservationLogEntity entity = RiskReservationLogEntity.builder()
                .id(UUID.randomUUID())
                .orderId(log.orderId())
                .clientOrderId(log.clientOrderId())
                .eventType(log.eventType().name())
                .amount(log.amount())
                .createdAt(Instant.now())
                .build();
        repository.save(entity);
    }
}
