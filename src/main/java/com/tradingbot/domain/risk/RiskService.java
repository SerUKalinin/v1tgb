package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.*;
import com.tradingbot.common.util.ClientOrderIdGenerator;
import com.tradingbot.common.util.MoneyMath;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.*;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.tracing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Сервис управления рисками.
 *
 * <p>Отвечает за проверку сигналов, резервирование и освобождение капитала,
 * управление состоянием рисков, аварийную остановку и синхронизацию баланса.</p>
 */
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final RiskStatePort riskStatePort;
    private final RiskStateReducer reducer;
    private final RiskReservationLogPort riskReservationLogPort;
    private final ExchangeFeasibilityPort feasibilityPort;
    private final OrderNormalizationService normalizationService;
    private final ExecutionLogger executionLogger;
    private final OutboxService outboxService;

    /**
     * Конструктор сервиса.
     *
     * @param riskStatePort порт для получения и сохранения состояния риска
     * @param reducer редьюсер состояния риска
     * @param riskReservationLogPort порт для логирования резервов капитала
     * @param feasibilityPort порт для проверки исполнимости ордера на бирже
     * @param normalizationService сервис для нормализации ордеров под биржевые ограничения
     * @param executionLogger логгер исполнения
     * @param outboxService сервис для отправки событий в outbox
     */
    public RiskService(
            RiskStatePort riskStatePort,
            RiskStateReducer reducer,
            RiskReservationLogPort riskReservationLogPort,
            ExchangeFeasibilityPort feasibilityPort,
            OrderNormalizationService normalizationService,
            ExecutionLogger executionLogger,
            OutboxService outboxService
    ) {
        this.riskStatePort = riskStatePort;
        this.reducer = reducer;
        this.riskReservationLogPort = riskReservationLogPort;
        this.feasibilityPort = feasibilityPort;
        this.normalizationService = normalizationService;
        this.executionLogger = executionLogger;
        this.outboxService = outboxService;
    }

    // ==================== MAIN FLOW ====================

    /**
     * Оценивает торговый сигнал и формирует заказ.
     *
     * <p>Выполняет последовательность шагов:
     * <ol>
     *     <li>Проверка состояния системы (HALT)</li>
     *     <li>Расчет объема сделки</li>
     *     <li>Нормализация под требования биржи</li>
     *     <li>Проверка возможности исполнения (Feasibility)</li>
     *     <li>Проверка лимитов капитала и резервирование</li>
     *     <li>Создание доменного объекта Order</li>
     * </ol></p>
     *
     * @param context контекст исполнения
     * @param signal событие торгового сигнала
     * @return опциональный заказ, если сигнал одобрен, иначе пустой Optional
     */
    @Transactional
    public Optional<Order> evaluateSignal(ExecutionContext context, SignalEvent signal) {
        IdentityContext identity = context.identity();
        log.info("[TRACE_FLOW] ENTER RiskService.evaluateSignal for identity: {}", identity);
        log.info("[TRACE_FLOW] Current RiskState: halted={}, balance={}, reserved={}",
                riskStatePort.get().isHalted(), riskStatePort.get().getBalance(), riskStatePort.get().getReservedMargin());

        RiskState state = riskStatePort.get();
        if (state.isHalted()) {
            log.warn("[TRACE_FLOW] EXIT RiskService - REJECTED: System is HALTED for identity: {}", identity);
            return Optional.empty();
        }

        BigDecimal rawQuantity = calculateQuantity(signal, state);
        log.info("[TRACE_FLOW] Calculated raw quantity: {} for identity: {}", rawQuantity, identity);

        NormalizedOrder normalized = normalizationService.normalize(
                new FeasibilityRequest(signal.getSymbol(), rawQuantity, signal.getPrice())
        );
        log.info("[TRACE_FLOW] Normalized order: qty={}, price={} for identity: {}", normalized.getQuantity(), normalized.getPrice(), identity);

        FeasibilityResult feasibility = feasibilityPort.check(
                new FeasibilityRequest(
                        normalized.getSymbol(),
                        normalized.getQuantity(),
                        normalized.getPrice()
                )
        );
        log.info("[TRACE_FLOW] Feasibility check: feasible={}, reason={} for identity: {}", feasibility.isFeasible(), feasibility.getReason(), identity);

        if (!feasibility.isFeasible()) {
            log.warn("[TRACE_FLOW] EXIT RiskService - REJECTED: Not feasible. Reason: {} for identity: {}", feasibility.getReason(), identity);
            return Optional.empty();
        }

        BigDecimal requiredCapital = normalized.getQuantity().multiply(normalized.getPrice());
        UUID orderId = IdentityFactory.deriveOrder(signal.getSignalId());

        RiskDecision decision = RiskPolicy.canReserve(state, orderId, requiredCapital);
        log.info("[TRACE_FLOW] RiskPolicy decision: approved={}, reason={} for identity: {}", decision.isApproved(), decision.getReason(), identity);

        if (!decision.isApproved()) {
            log.warn("[TRACE_FLOW] EXIT RiskService - REJECTED: Policy violation. Reason: {} for identity: {}", decision.getReason(), identity);
            return Optional.empty();
        }

        UUID eventId = IdentityFactory.deriveEventId(orderId, "capital-reserved");
        RiskEvent.CapitalReserved event = new RiskEvent.CapitalReserved(
                eventId.toString(),
                orderId,
                requiredCapital
        );

        RiskState newState = reducer.reduce(state, event);

        log.info("[TRACE_FLOW] Persisting risk state change for identity: {}", identity);
        riskStatePort.markEventProcessed(
                eventId,
                newState,
                event
        );
        log.info("[TRACE_FLOW] Risk state persisted for identity: {}", identity);

        logReservation(orderId, RiskReservationEventType.RESERVE, requiredCapital);

        Order order = Order.createPendingExecution(
                orderId,
                ClientOrderIdGenerator.generate(orderId),
                signal.getSymbol(),
                signal.getType() == SignalType.BUY ? OrderSide.BUY : OrderSide.SELL,
                OrderType.MARKET,
                normalized.getQuantity(),
                normalized.getPrice(),
                signal.getStrategyId(),
                signal.getSignalId()
        );
        log.info("[TRACE_FLOW] EXIT RiskService.evaluateSignal - APPROVED for identity: {}", identity);

        return Optional.of(order);
    }

    // ==================== EVENTS ====================

    /**
     * Публикует событие риска и обновляет состояние RiskState.
     *
     * <p>Игнорирует события, если система в HALT, кроме TradingHalted.</p>
     *
     * @param event событие риска для публикации
     */
    public void publish(RiskEvent event) {
        RiskState state = riskStatePort.get();

        if (state.isHalted() && !(event instanceof RiskEvent.TradingHalted)) {
            return;
        }

        UUID eventId;
        try {
            eventId = UUID.fromString(event.getEventId());
        } catch (IllegalArgumentException e) {
            eventId = UUID.nameUUIDFromBytes(event.getEventId().getBytes());
        }

        if (riskStatePort.isEventProcessed(eventId)) {
            return;
        }

        BigDecimal reservedBefore = null;
        if (event instanceof RiskEvent.CapitalReleased e) {
            reservedBefore = state.getActiveReservations().get(e.orderId());
        }

        RiskState newState = reducer.reduce(state, event);
        riskStatePort.markEventProcessed(eventId, newState, event);

        if (event instanceof RiskEvent.CapitalReserved e) {
            logReservation(e.orderId(), RiskReservationEventType.RESERVE, e.amount());
        }

        if (event instanceof RiskEvent.CapitalReleased e && reservedBefore != null) {
            logReservation(e.orderId(), RiskReservationEventType.RELEASE, reservedBefore);
        }
    }

    // ==================== COMMANDS ====================

    /**
     * Пытается зарезервировать указанную сумму капитала для ордера.
     *
     * @param context контекст исполнения
     * @param amount сумма для резервирования
     * @return решение по риску (одобрено/отклонено)
     */
    public RiskDecision reserve(ExecutionContext context, BigDecimal amount) {
        UUID orderId = UUID.fromString(context.business().orderId());
        RiskState state = riskStatePort.get();
        RiskDecision decision = RiskPolicy.canReserve(state, orderId, amount);

        if (decision.isApproved()) {
            UUID eventId = IdentityFactory.deriveEventId(orderId, "capital-reserved-" + context.attempt().attemptNumber());
            RiskEvent.CapitalReserved event = new RiskEvent.CapitalReserved(eventId.toString(), orderId, amount);

            RiskState newState = reducer.reduce(state, event);
            riskStatePort.markEventProcessed(eventId, newState, event);
            logReservation(orderId, RiskReservationEventType.RESERVE, amount);
            log.info("[RISK] Reserved {} for order {}", amount, orderId);
        }

        return decision;
    }

    /**
     * Освобождает ранее зарезервированные средства.
     *
     * @param context контекст исполнения
     * @param amount сумма для освобождения
     * @param reason причина освобождения
     */
    public void release(ExecutionContext context, BigDecimal amount, String reason) {
        UUID orderId = UUID.fromString(context.business().orderId());
        RiskState state = riskStatePort.get();
        UUID eventId = IdentityFactory.deriveEventId(orderId, "capital-released-" + Instant.now().toEpochMilli());
        RiskEvent.CapitalReleased event = new RiskEvent.CapitalReleased(eventId.toString(), orderId, amount, reason);

        RiskState newState = reducer.reduce(state, event);
        riskStatePort.markEventProcessed(eventId, newState, event);
        logReservation(orderId, RiskReservationEventType.RELEASE, amount);
        log.info("[RISK] Released {} for order {}, reason: {}", amount, orderId, reason);
    }

    /**
     * Включает аварийную остановку торговли.
     *
     * @param reason причина остановки
     */
    public void emergencyStop(String reason) {
        RiskState state = riskStatePort.get();
        RiskState newState = state.toBuilder()
                .halted(true)
                .build();

        riskStatePort.save(newState);
        log.error("[RISK] EMERGENCY STOP: {}", reason);
    }

    /**
     * Возобновляет торговлю после аварийной остановки.
     */
    public void resumeTrading() {
        RiskState state = riskStatePort.get();
        RiskState newState = state.toBuilder()
                .halted(false)
                .build();

        riskStatePort.save(newState);
        log.info("[RISK] Trading resumed");
    }

    /**
     * Инициализирует состояние рисков.
     *
     * @param state начальное состояние рисков
     */
    public void initialize(RiskState state) {
        riskStatePort.save(state);
    }

    /**
     * Синхронизирует баланс с фактическим значением.
     *
     * @param actualBalance фактический баланс
     */
    public void syncBalance(BigDecimal actualBalance) {
        RiskState state = riskStatePort.get();
        RiskState newState = state.toBuilder()
                .balance(actualBalance)
                .build();
        riskStatePort.save(newState);
        log.info("[RISK] Balance synchronized to: {}", actualBalance);
    }

    /**
     * Возвращает текущее состояние рисков.
     *
     * @return состояние рисков
     */
    public RiskState getState() {
        return riskStatePort.get();
    }

    // ==================== INTERNAL ====================

    /**
     * Логирует операцию резервирования или освобождения капитала.
     *
     * @param orderId идентификатор ордера
     * @param type тип события (RESERVE/RELEASE)
     * @param amount сумма
     */
    private void logReservation(UUID orderId, RiskReservationEventType type, BigDecimal amount) {
        riskReservationLogPort.append(
                new RiskReservationLog(orderId, "N/A", type, amount)
        );
    }

    /**
     * Вычисляет количество актива для сделки на основе сигнала и состояния рисков.
     *
     * @param signal сигнал
     * @param state состояние рисков
     * @return расчетное количество актива
     */
    private BigDecimal calculateQuantity(SignalEvent signal, RiskState state) {
        BigDecimal riskPercent = new BigDecimal("0.01"); // 1% риск
        BigDecimal baseCapital = state.getTotalEquity().max(state.getBalance());

        if (baseCapital.compareTo(BigDecimal.ZERO) <= 0
                || signal.getPrice() == null
                || signal.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("0.001");
        }

        return baseCapital
                .multiply(riskPercent)
                .divide(signal.getPrice(), 8, RoundingMode.HALF_UP);
    }

    /**
     * Парсит строку в UUID, при ошибке формирует UUID на основе имени.
     *
     * @param eventId строка идентификатора события
     * @return UUID события
     */
    private UUID parseEventId(String eventId) {
        try {
            return UUID.fromString(eventId);
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(eventId.getBytes());
        }
    }
}