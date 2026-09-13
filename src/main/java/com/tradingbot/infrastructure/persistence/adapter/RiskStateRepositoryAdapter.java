package com.tradingbot.infrastructure.persistence.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStatePort;
import com.tradingbot.infrastructure.persistence.entity.RiskEventEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.mapper.RiskStateMapper;
import com.tradingbot.infrastructure.persistence.repository.RiskEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Адаптер для работы с состоянием рисков через JPA репозитории.
 *
 * <p>Реализует {@link RiskStatePort} и обеспечивает:
 * <ul>
 *     <li>Загрузку и сохранение единственного состояния риска ({@link RiskState})</li>
 *     <li>Отслеживание и логирование обработанных событий риска ({@link RiskEvent})</li>
 *     <li>Идемпотентное применение событий</li>
 * </ul></p>
 *
 * <p>Использует паттерн singleton entity для RiskState, гарантируя,
 * что существует только одна запись состояния системы.</p>
 */
@Component
@RequiredArgsConstructor
@Primary
@Slf4j
public class RiskStateRepositoryAdapter implements RiskStatePort {

    private final RiskStateRepository riskStateRepository;
    private final RiskEventRepository eventRepository;
    private final RiskStateMapper riskStateMapper;
    private final ObjectMapper objectMapper;

    /** ID единственного агрегата RiskState */
    private static final String AGGREGATE_ID = RiskStateEntity.SINGLETON_ID;

    /**
     * Возвращает текущее состояние рисков.
     *
     * <p>Если запись состояния отсутствует, создается новая с нулевыми значениями.</p>
     *
     * @return текущее состояние риска
     */
    @Override
    @Transactional
    public RiskState get() {
        RiskStateEntity entity = loadOrInit();
        return riskStateMapper.toDomain(entity);
    }

    /**
     * Сохраняет текущее состояние риска.
     *
     * <p>Обновляет поля существующей сущности и фиксирует время обновления.</p>
     *
     * @param state состояние риска для сохранения
     */
    @Override
    @Transactional
    public void save(RiskState state) {
        RiskStateEntity entity = loadOrInit();
        riskStateMapper.updateEntity(entity, state);
        entity.setUpdatedAt(Instant.now());
        riskStateRepository.saveAndFlush(entity);
    }

    /**
     * Проверяет, обработано ли событие риска.
     *
     * @param eventId идентификатор события
     * @return true, если событие уже обработано
     */
    @Override
    public boolean isEventProcessed(UUID eventId) {
        return eventRepository.existsByEventId(eventId);
    }

    /**
     * Помечает событие риска как обработанное и сохраняет его в репозиторий.
     *
     * <p>Если событие уже существует, обновляет текущее состояние риска.</p>
     *
     * @param eventId идентификатор события
     * @param state текущее состояние риска
     * @param event событие риска
     */
    @Override
    @Transactional
    public void markEventProcessed(UUID eventId, RiskState state, RiskEvent event) {
        if (eventRepository.existsByEventId(eventId)) {
            log.warn("[RISK] Event {} already exists, skipping event log insert", eventId);
            save(state);
            return;
        }

        long nextVersion = eventRepository.findMaxVersionByAggregateId(AGGREGATE_ID).orElse(0L) + 1;
        RiskState stateWithVersion = state.toBuilder().version(nextVersion).build();

        save(stateWithVersion);

        try {
            RiskEventEntity eventEntity = RiskEventEntity.builder()
                    .eventId(eventId)
                    .aggregateId(AGGREGATE_ID)
                    .version(nextVersion)
                    .eventType(event.getClass().getSimpleName())
                    .payload(objectMapper.writeValueAsString(event))
                    .build();
            eventRepository.save(eventEntity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist risk event", e);
        }
    }

    /**
     * Загружает существующее состояние риска или инициализирует новое.
     *
     * <p>Используется для реализации singleton pattern и защиты от гонок создания записи.</p>
     *
     * @return сущность состояния риска
     */
    private RiskStateEntity loadOrInit() {
        return riskStateRepository.findByIdForUpdate(AGGREGATE_ID)
                .orElseGet(() -> {
                    try {
                        RiskStateEntity newEntity = new RiskStateEntity();
                        newEntity.setAvailableBalance(java.math.BigDecimal.ZERO);
                        newEntity.setReservedMargin(java.math.BigDecimal.ZERO);
                        newEntity.setTotalEquity(java.math.BigDecimal.ZERO);
                        newEntity.setHalted(false);
                        newEntity.setActiveReservations(new java.util.HashMap<>());
                        newEntity.setProcessedEventIds(new java.util.HashSet<>());
                        newEntity.setVersion(null);
                        return riskStateRepository.saveAndFlush(newEntity);
                    } catch (org.springframework.dao.DataIntegrityViolationException | org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                        log.info("[RISK] Singleton entity already exists, reloading...");
                        return riskStateRepository.findByIdForUpdate(AGGREGATE_ID).orElseThrow();
                    }
                });
    }
}