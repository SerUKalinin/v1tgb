package com.tradingbot.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RiskStateMapper {

    public RiskState toDomain(RiskStateEntity entity) {
        if (entity == null) {
            return RiskState.empty();
        }

        return RiskState.builder()
                .balance(Objects.requireNonNullElse(entity.getAvailableBalance(), BigDecimal.ZERO))
                .totalEquity(Objects.requireNonNullElse(entity.getTotalEquity(), BigDecimal.ZERO))
                .halted(entity.isHalted())
                .version(entity.getVersion() != null ? entity.getVersion() : 0L)
                .activeReservations(Objects.requireNonNullElse(entity.getActiveReservations(), Map.of()))
                .processedEventIds(Objects.requireNonNullElse(entity.getProcessedEventIds(), Set.of()))
                .build();
    }

    public void updateEntity(RiskStateEntity entity, RiskState state) {
        if (entity == null || state == null) return;
        entity.setAvailableBalance(state.getBalance());
        entity.setReservedMargin(state.getReserved());
        entity.setTotalEquity(state.getTotalEquity());
        entity.setHalted(state.isHalted());
        entity.setActiveReservations(state.getActiveReservations());
        entity.setProcessedEventIds(state.getProcessedEventIds());
    }
}
