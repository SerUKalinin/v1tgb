package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.risk.RiskReservationEventType;
import com.tradingbot.domain.risk.RiskReservationLog;
import com.tradingbot.domain.risk.RiskReservationLogPort;
import com.tradingbot.infrastructure.persistence.entity.RiskReservationLogEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskReservationLogRepository;
import com.tradingbot.tracing.IdentityFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Адаптер для сохранения логов резервирования капитала.
 */
@Component
@RequiredArgsConstructor
public class RiskReservationLogAdapter
        implements RiskReservationLogPort {

    private final RiskReservationLogRepository repository;

    @Override
    public void append(
            RiskReservationLog log
    ) {

        UUID eventId =
                log.eventId();

        /*
         * Для всех новых операций eventId обязан приходить
         * из доменного RiskEvent.
         *
         * Fallback оставлен только как defensive protection
         * для старых callers.
         */
        if (eventId == null) {

            eventId =
                    IdentityFactory.deriveEventId(
                            log.orderId(),
                            "risk-log-"
                                    + log.eventType()
                                    + "-"
                                    + log.amount()
                    );
        }

        RiskReservationLogEntity entity =
                RiskReservationLogEntity.builder()
                        .id(eventId)
                        .orderId(log.orderId())
                        .clientOrderId(log.clientOrderId())
                        .eventType(log.eventType().name())
                        .amount(log.amount())
                        .createdAt(Instant.now())
                        .build();

        repository.save(entity);
    }
}