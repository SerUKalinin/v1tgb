package com.tradingbot.infrastructure.persistence.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Converter;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Converter
public class ActiveReservationsConverter extends JsonAttributeConverter<Map<UUID, BigDecimal>> {
    public ActiveReservationsConverter() {
        super(new TypeReference<Map<UUID, BigDecimal>>() {});
    }

    @Override
    protected Map<UUID, BigDecimal> getDefaultValue() {
        return Map.of();
    }
}
