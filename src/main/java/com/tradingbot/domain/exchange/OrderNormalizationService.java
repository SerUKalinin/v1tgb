package com.tradingbot.domain.exchange;

/**
 * Сервис нормализации входных параметров ордера.
 * <p>
 * Приводит входной запрос к единому доменному формату {@link NormalizedOrder},
 * выполняя преобразование, очистку и приведение значений к внутренним правилам
 * системы перед дальнейшей обработкой (risk/execution pipeline).
 */
public interface OrderNormalizationService {

    /**
     * Нормализует входной запрос на проверку/создание ордера.
     *
     * @param request входные параметры сделки
     * @return нормализованная модель ордера, готовая для обработки внутри системы
     */
    NormalizedOrder normalize(FeasibilityRequest request);
}