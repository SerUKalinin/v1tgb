package com.tradingbot.infrastructure.execution.exchange;

import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Реализация сервиса проверки состояния ордера и баланса на бирже.
 *
 * <p>Использует BinanceClient для получения фактического состояния аккаунта.
 * Для testnet base-url задаётся через application-testnet.yaml.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeOrderQueryServiceImpl implements ExchangeOrderQueryService {

    private final BinanceClient binanceClient;

    /**
     * Проверяет, был ли ордер уже исполнен или существует ли он на бирже.
     *
     * <p>Текущая реализация пока не выполняет отдельный поиск ордера
     * и сохраняет прежнее поведение.</p>
     *
     * @param clientOrderId уникальный идентификатор ордера в системе
     * @return false, пока отдельный reconciliation order-status flow не реализован
     */
    @Override
    public boolean isOrderAlreadyExecuted(String clientOrderId) {
        log.debug(
                "[EXCHANGE-QUERY] Checking status for clientOrderId: {}",
                clientOrderId
        );

        return false;
    }

    /**
     * Возвращает фактический доступный баланс указанного актива
     * непосредственно из Binance account information.
     *
     * @param asset код актива, например BTC или USDT
     * @return фактический free balance актива
     */
    @Override
    public BigDecimal getAvailableBalance(String asset) {
        log.debug(
                "[EXCHANGE-QUERY] Getting available balance for asset: {}",
                asset
        );

        Map accountInfo = binanceClient.getAccountInfo();

        if (accountInfo == null) {
            throw new IllegalStateException(
                    "Binance account info is null"
            );
        }

        Object balancesObject = accountInfo.get("balances");

        if (!(balancesObject instanceof List<?> balances)) {
            throw new IllegalStateException(
                    "Binance account info does not contain balances"
            );
        }

        for (Object item : balances) {
            if (!(item instanceof Map<?, ?> balance)) {
                continue;
            }

            Object assetValue = balance.get("asset");

            if (assetValue == null
                    || !asset.equalsIgnoreCase(assetValue.toString())) {
                continue;
            }

            Object freeValue = balance.get("free");

            if (freeValue == null) {
                throw new IllegalStateException(
                        "Binance balance for asset " + asset + " has no free value"
                );
            }

            BigDecimal freeBalance = new BigDecimal(
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
                "Asset " + asset + " not found in Binance account balances"
        );
    }

    /**
     * Получает статус ордера на бирже по exchangeOrderId.
     *
     * <p>Отдельный reconciliation flow пока не реализован.</p>
     *
     * @param exchangeOrderId идентификатор ордера на бирже
     * @return статус исполнения ордера
     * @throws UnsupportedOperationException пока метод не реализован
     */
    @Override
    public ExecutionResult getOrderStatus(String exchangeOrderId) {
        throw new UnsupportedOperationException(
                "Not implemented yet"
        );
    }
}