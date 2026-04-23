package com.tradingbot.infrastructure.execution.exchange;

import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Реализация сервиса проверки состояния ордера на бирже.
 * На данном этапе является заглушкой (MOCK).
 */
@Slf4j
@Service
public class ExchangeOrderQueryServiceImpl implements ExchangeOrderQueryService {

    /**
     * Проверяет, был ли ордер уже исполнен или существует ли он на бирже.
     * 
     * @param clientOrderId уникальный идентификатор ордера в нашей системе
     * @return false (MOCK), в будущем будет интеграция с BinanceClient
     */
    @Override
    public boolean isOrderAlreadyExecuted(String clientOrderId) {
        log.debug("[EXCHANGE-QUERY] Checking status for clientOrderId: {} (MOCK)", clientOrderId);
        
        // TODO: Интеграция с BinanceClient:
        // try {
        //     var order = binanceClient.getOrder(clientOrderId);
        //     return order != null && (order.isFilled() || order.isPartiallyFilled() || order.isNew());
        // } catch (Exception e) { return false; }
        
        return false;
    }
}
