package com.tradingbot.application.service.order;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Сервис верхнего уровня (OMS orchestration layer), отвечающий за маршрутизацию торговых сигналов
 * в pipeline создания ордеров.
 *
 * <p>Фильтрует неисполняемые сигналы и делегирует обработку в OrderApplicationService.</p>
 *
 * <p>Является точкой входа для signal-driven workflow на уровне application слоя.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderManagementService {

    private final OrderApplicationService orderApplicationService;

    /**
     * Обрабатывает входящий торговый сигнал и инициирует создание ордера при необходимости.
     *
     * <p>Логика:
     * <ul>
     *     <li>игнорирует сигналы типа HOLD</li>
     *     <li>логирует маршрутизацию сигнала</li>
     *     <li>делегирует выполнение OrderApplicationService</li>
     * </ul>
     *
     * @param event торговый сигнал
     */
    public void onSignal(SignalEvent event) {

        if (event.getType() == SignalType.HOLD) {
            return;
        }

        log.info("[OMS] routing {} {}", event.getSymbol(), event.getType());

        orderApplicationService.handleSignal(event);
    }
}