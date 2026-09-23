package com.tradingbot.infrastructure.execution.exchange;

import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Реализация сервиса запросов состояния ордеров и балансов.
 *
 * <p>Запрос статуса ордера делегируется в ExecutionPort,
 * который инкапсулирует конкретный Binance API flow.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeOrderQueryServiceImpl
        implements ExchangeOrderQueryService {

    private final ExecutionPort executionPort;
    private final BinanceClient binanceClient;

    /**
     * Проверяет, был ли ордер уже исполнен.
     *
     * <p>Отдельный lightweight lookup пока не используется
     * в production execution path.</p>
     */
    @Override
    public boolean isOrderAlreadyExecuted(String clientOrderId) {
        log.debug(
                "[EXCHANGE-QUERY] Checking execution status: clientOrderId={}",
                clientOrderId
        );

        return false;
    }

    /**
     * Возвращает фактический доступный баланс актива.
     */
    @Override
    public BigDecimal getAvailableBalance(String asset) {

        log.debug(
                "[EXCHANGE-QUERY] Getting available balance: asset={}",
                asset
        );

        Map accountInfo = binanceClient.getAccountInfo();

        if (accountInfo == null) {
            throw new IllegalStateException(
                    "Binance account info is null"
            );
        }

        Object balancesObject =
                accountInfo.get("balances");

        if (!(balancesObject instanceof List<?> balances)) {
            throw new IllegalStateException(
                    "Binance account info does not contain balances"
            );
        }

        for (Object item : balances) {

            if (!(item instanceof Map<?, ?> balance)) {
                continue;
            }

            Object assetValue =
                    balance.get("asset");

            if (assetValue == null
                    || !asset.equalsIgnoreCase(
                    assetValue.toString())) {
                continue;
            }

            Object freeValue =
                    balance.get("free");

            if (freeValue == null) {
                throw new IllegalStateException(
                        "Binance balance for asset "
                                + asset
                                + " has no free value"
                );
            }

            BigDecimal freeBalance =
                    new BigDecimal(
                            freeValue.toString()
                    );

            log.info(
                    "[EXCHANGE-QUERY] Actual exchange balance: asset={}, free={}",
                    asset,
                    freeBalance
            );

            return freeBalance;
        }

        throw new IllegalStateException(
                "Asset " + asset
                        + " not found in Binance account balances"
        );
    }

    /**
     * Получает актуальное состояние ордера на бирже.
     *
     * <p>Запрос передаётся в ExecutionPort,
     * где выполняется реальный Binance API request.</p>
     */
    @Override
    public ExecutionResult getOrderStatus(
            String symbol,
            String clientOrderId
    ) {

        log.info(
                "[EXCHANGE-QUERY] Querying order status: symbol={}, clientOrderId={}",
                symbol,
                clientOrderId
        );

        return executionPort.getOrderStatus(
                symbol,
                clientOrderId
        );
    }
}