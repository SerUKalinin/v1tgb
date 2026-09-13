package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.execution.SignalClaimPort;
import com.tradingbot.infrastructure.persistence.entity.SignalClaimEntity;
import com.tradingbot.infrastructure.persistence.repository.SignalClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

/**
 * Адаптер для работы с сигналами исполнения через JPA репозиторий.
 *
 * <p>Реализует {@link SignalClaimPort} и обеспечивает:
 * <ul>
 *     <li>Проверку, был ли сигнал уже обработан</li>
 *     <li>Идемпотентное резервирование сигнала для исполнения</li>
 * </ul></p>
 *
 * <p>Используется в Execution Pipeline для гарантии одноразового claim сигнала.</p>
 */
@Component
@RequiredArgsConstructor
public class SignalClaimAdapter implements SignalClaimPort {

    private final SignalClaimRepository repository;

    /**
     * Проверяет, существует ли уже claim для указанного сигнала.
     *
     * @param signalId идентификатор сигнала
     * @return true, если сигнал уже был зарезервирован (claimed)
     */
    @Override
    public boolean exists(UUID signalId) {
        return repository.existsById(signalId);
    }

    /**
     * Пытается зарезервировать сигнал для исполнения.
     *
     * <p>Если сигнал уже зарезервирован, операция игнорируется (идемпотентно).</p>
     *
     * @param signalId идентификатор сигнала
     */
    @Override
    @Transactional(propagation = REQUIRES_NEW)
    public void claim(UUID signalId) {
        try {
            repository.saveAndFlush(SignalClaimEntity.builder()
                    .signalId(signalId)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Уже зарезервировано — игнорируем
        }
    }
}