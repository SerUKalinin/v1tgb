package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.execution.SignalClaimPort;
import com.tradingbot.infrastructure.persistence.entity.SignalClaimEntity;
import com.tradingbot.infrastructure.persistence.repository.SignalClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Адаптер для работы с claim торгового сигнала.
 *
 * <p>Claim должен быть частью той же транзакции,
 * в которой выполняются:
 *
 * <pre>
 * SignalClaim
 *      +
 * Risk
 *      +
 * Order
 *      +
 * Outbox
 * </pre>
 *
 * <p>Поэтому REQUIRES_NEW здесь запрещён.
 */
@Component
@RequiredArgsConstructor
public class SignalClaimAdapter
        implements SignalClaimPort {

    private final SignalClaimRepository repository;

    /**
     * Проверяет наличие claim.
     *
     * @param signalId signal identity
     * @return true если signal уже claimed
     */
    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID signalId) {
        return repository.existsById(signalId);
    }

    /**
     * Создаёт claim внутри текущей transaction.
     *
     * <p>Никакого REQUIRES_NEW.
     *
     * <p>Если другая transaction конкурентно успела создать
     * тот же signal claim, database unique constraint отклонит
     * вторую вставку. Эта transaction будет rollback-нута.
     *
     * <p>Это безопаснее, чем отдельный committed claim:
     * если дальнейший Risk/Order/Outbox pipeline падает,
     * signal claim откатывается вместе с business transaction.
     *
     * @param signalId signal identity
     */
    @Override
    @Transactional
    public void claim(UUID signalId) {

        if (signalId == null) {
            throw new IllegalArgumentException(
                    "signalId не должен быть null"
            );
        }

        try {

            repository.saveAndFlush(
                    SignalClaimEntity.builder()
                            .signalId(signalId)
                            .build()
            );

        } catch (DataIntegrityViolationException e) {

            /*
             * Важно:
             *
             * Здесь НЕ глотаем DataIntegrityViolationException.
             *
             * В случае конкурентного claim transaction должна
             * rollback-нуться, чтобы:
             *
             * 1. не сделать ложный success;
             * 2. не ACK-нуть candle;
             * 3. не скрыть race condition.
             */
            throw e;
        }
    }
}