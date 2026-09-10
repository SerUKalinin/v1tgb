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
 *
 * <p>Реализует {@link RiskReservationLogPort} и отвечает за
 * запись событий резервирования/освобождения капитала в персистентное хранилище.</p>
 *
 * <p>Используется для аудита риск-операций и трассировки движения капитала
 * в execution pipeline.</p>
 */
@Component
@RequiredArgsConstructor
public class RiskReservationLogAdapter implements RiskReservationLogPort {

    /** Репозиторий для сохранения логов резервирования */
    private final RiskReservationLogRepository repository;

    /**
     * Добавляет запись о событии резервирования капитала.
     *
     * <p>Преобразует доменную модель {@link RiskReservationLog} в
     * {@link RiskReservationLogEntity} и сохраняет в БД.</p>
     *
     * @param log доменный лог события резервирования
     */
    @Override
    public void append(RiskReservationLog log) {
        RiskReservationLogEntity entity = RiskReservationLogEntity.builder()
                .id(IdentityFactory.deriveEventId(log.orderId(), "risk-log-" + log.eventType()))
                .orderId(log.orderId())
                .clientOrderId(log.clientOrderId())
                .eventType(log.eventType().name())
                .amount(log.amount())
                .createdAt(Instant.now())
                .build();

        repository.save(entity);
    }
}