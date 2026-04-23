package com.tradingbot.infrastructure.execution.exchange;

import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

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
        return false;
    }

    @Override
    public BigDecimal getAvailableBalance(String asset) {
        log.debug("[EXCHANGE-QUERY] Getting available balance for asset: {} (MOCK)", asset);
        return new BigDecimal("10000"); // Mock balance
    }
}
