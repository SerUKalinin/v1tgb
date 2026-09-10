package com.tradingbot.infrastructure.persistence.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import lombok.extern.slf4j.Slf4j;

/**
 * Базовый JPA-конвертер для сериализации/десериализации объектов в JSON.
 *
 * <p>Используется для хранения сложных структур данных в виде JSON-строки в базе данных
 * и восстановления их обратно в объектную модель.</p>
 *
 * <p>Реализует общий механизм преобразования через Jackson ObjectMapper
 * с поддержкой generic-типов через {@link TypeReference}.</p>
 *
 * @param <T> тип конвертируемого объекта
 */
@Slf4j
public abstract class JsonAttributeConverter<T> implements AttributeConverter<T, String> {

    /**
     * Общий ObjectMapper для сериализации/десериализации JSON.
     */
    private static final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * Описание generic-типа для корректной десериализации.
     */
    private final TypeReference<T> typeReference;

    /**
     * Конструктор базового JSON-конвертера.
     *
     * @param typeReference описание типа для Jackson десериализации
     */
    protected JsonAttributeConverter(TypeReference<T> typeReference) {
        this.typeReference = typeReference;
    }

    /**
     * Преобразует объект в JSON-строку для сохранения в базе данных.
     *
     * @param attribute объект для сериализации
     * @return JSON-строка или null, если объект null или произошла ошибка сериализации
     */
    @Override
    public String convertToDatabaseColumn(T attribute) {
        if (attribute == null) return null;
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Error serializing attribute to JSON", e);
            return null;
        }
    }

    /**
     * Преобразует JSON-строку из базы данных в объект.
     *
     * <p>При ошибке десериализации возвращает значение по умолчанию.</p>
     *
     * @param dbData JSON-строка из базы данных
     * @return десериализованный объект или значение по умолчанию
     */
    @Override
    public T convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return getDefaultValue();
        try {
            return objectMapper.readValue(dbData, typeReference);
        } catch (JsonProcessingException e) {
            log.error("Error deserializing JSON to attribute", e);
            return getDefaultValue();
        }
    }

    /**
     * Значение по умолчанию, возвращаемое при null или ошибке десериализации.
     *
     * @return дефолтное значение типа T
     */
    protected abstract T getDefaultValue();
}