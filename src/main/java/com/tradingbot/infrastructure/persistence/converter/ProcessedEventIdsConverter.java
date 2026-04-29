package com.tradingbot.infrastructure.persistence.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Converter;
import java.util.Set;

@Converter
public class ProcessedEventIdsConverter extends JsonAttributeConverter<Set<String>> {
    public ProcessedEventIdsConverter() {
        super(new TypeReference<Set<String>>() {});
    }

    @Override
    protected Set<String> getDefaultValue() {
        return Set.of();
    }
}
