package com.tradingbot.infrastructure.persistence.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Converter;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * JPA-конвертер для сериализации/десериализации активных резервов капитала.
 *
 * <p>Преобразует структуру {@code Map<UUID, BigDecimal>} в JSON-строку и обратно
 * при сохранении в базу данных и чтении из неё.</p>
 *
 * <p>Используется для хранения активных резервов по ордерам внутри RiskStateEntity.</p>
 */
@Converter
public class ActiveReservationsConverter extends JsonAttributeConverter<Map<UUID, BigDecimal>> {

    /**
     * Конструктор конвертера.
     *
     * <p>Инициализирует Jackson TypeReference для корректной
     * десериализации generic-структуры Map&lt;UUID, BigDecimal&gt;.</p>
     */
    public ActiveReservationsConverter() {
        super(new TypeReference<Map<UUID, BigDecimal>>() {});
    }

    /**
     * Возвращает значение по умолчанию, если поле в базе равно null.
     *
     * @return пустая Map (immutable)
     */
    @Override
    protected Map<UUID, BigDecimal> getDefaultValue() {
        return Map.of();
    }
}