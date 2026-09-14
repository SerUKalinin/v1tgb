package com.tradingbot.infrastructure.execution.exchange;

import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Реализация сервиса проверки состояния ордера на бирже.
 *
 * <p>Текущая реализация является заглушкой (MOCK) и используется для разработки
 * и тестирования без реального обращения к бирже.</p>
 *
 * <p>В дальнейшем будет заменена на полноценную интеграцию с Binance API
 * через {@code BinanceClient} или соответствующий адаптер.</p>
 */
@Slf4j
@Service
public class ExchangeOrderQueryServiceImpl implements ExchangeOrderQueryService {

    /**
     * Проверяет, был ли ордер уже исполнен или существует ли он на бирже.
     *
     * <p>Mock-реализация всегда возвращает {@code false}.</p>
     *
     * @param clientOrderId уникальный идентификатор ордера в системе
     * @return всегда {@code false} в текущей реализации
     */
    @Override
    public boolean isOrderAlreadyExecuted(String clientOrderId) {
        log.debug("[EXCHANGE-QUERY] Checking status for clientOrderId: {} (MOCK)", clientOrderId);
        return false;
    }

    /**
     * Возвращает доступный баланс по указанному активу.
     *
     * <p>Mock-реализация возвращает фиксированное значение {@code 10000}.</p>
     *
     * @param asset код актива (например BTC, USDT)
     * @return фиксированный баланс {@code 10000}
     */
    @Override
    public BigDecimal getAvailableBalance(String asset) {
        log.debug("[EXCHANGE-QUERY] Getting available balance for asset: {} (MOCK)", asset);
        return new BigDecimal("10000");
    }

    /**
     * Получает статус ордера на бирже по exchangeOrderId.
     *
     * <p>Метод не реализован и выбрасывает исключение.</p>
     *
     * @param exchangeOrderId идентификатор ордера на бирже
     * @return статус исполнения ордера
     * @throws UnsupportedOperationException всегда
     */
    @Override
    public ExecutionResult getOrderStatus(String exchangeOrderId) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}