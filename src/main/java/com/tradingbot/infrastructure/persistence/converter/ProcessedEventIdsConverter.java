package com.tradingbot.infrastructure.persistence.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Converter;
import java.util.Set;

/**
 * JPA-конвертер для хранения множества идентификаторов обработанных событий в виде JSON.
 *
 * <p>Используется для сериализации {@link Set} строк в JSON-строку для базы данных
 * и десериализации обратно в {@link Set} при чтении.</p>
 *
 * <p>Расширяет {@link JsonAttributeConverter} с типом {@code Set<String>}.</p>
 */
@Converter
public class ProcessedEventIdsConverter extends JsonAttributeConverter<Set<String>> {

    /**
     * Конструктор конвертера, инициализирующий {@link TypeReference} для Jackson.
     */
    public ProcessedEventIdsConverter() {
        super(new TypeReference<Set<String>>() {});
    }

    /**
     * Значение по умолчанию, возвращаемое при null или ошибке десериализации.
     *
     * @return пустое множество
     */
    @Override
    protected Set<String> getDefaultValue() {
        return Set.of();
    }
}