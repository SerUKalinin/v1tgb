package com.tradingbot.application.service.risk;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.event.SystemEvents;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.service.execution.PositionRebuildService;
import com.tradingbot.application.service.system.AdminNotificationService;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.binance.BinanceStatusMapper;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.tracing.*;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Сервис реконсиляции состояния торговой системы.
 *
 * <p>Отвечает за:
 * <ul>
 *     <li>синхронизацию балансов с биржей</li>
 *     <li>восстановление застрявших ордеров</li>
 *     <li>очистку и восстановление outbox событий</li>
 *     <li>reconciliation ордеров с состоянием биржи</li>
 * </ul>
 *
 * <p>Является критическим компонентом обеспечения консистентности между:
 * доменной моделью, биржей и инфраструктурными событиями.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReconciliationService {

    /**
     * Порт доступа к ордерам в хранилище.
     */
    private final OrderRepositoryPort orderRepository;

    /**
     * Репозиторий outbox событий.
     */
    private final OutboxEventRepository outboxRepository;

    /**
     * Сервис запросов состояния ордеров на бирже.
     */
    private final ExchangeOrderQueryService exchangeQueryService;

    /**
     * Сервис компенсации состояния ордеров (освобождение резервов).
     */
    private final OrderCompensationService orderCompensationService;

    /**
     * Движок риск-менеджмента, содержащий актуальное состояние системы.
     */
    private final RiskEngine riskEngine;

    /**
     * Сервис уведомлений администратора.
     */
    private final AdminNotificationService notifications;

    /**
     * Сервис восстановления позиций.
     */
    private final PositionRebuildService positionRebuildService;

    /**
     * Менеджер состояния системы (cold start / ready / recovery).
     */
    private final SystemStateManager stateManager;

    /**
     * Валидатор допустимых переходов состояний ордера.
     */
    private final TransitionValidator transitionValidator;

    /**
     * Логгер событий исполнения.
     */
    private final ExecutionLogger executionLogger;

    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(2);
    private static final Duration DRIFT_DETECTION_WINDOW = Duration.ofSeconds(45);
    private static final BigDecimal DRIFT_THRESHOLD = new BigDecimal("0.01");
    private static final Duration RECONCILIATION_GRACE_PERIOD = Duration.ofSeconds(30);

    private Instant lastReconcileTimestamp = Instant.now();

    /**
     * Обработка события холодного старта системы.
     *
     * @param event событие cold start
     */
    @EventListener
    public void onColdStart(SystemEvents.ColdStartDetectedEvent event) {
        log.info("[RECON] Handling Cold Start event. Forcing reconciliation...");
        reconcileAll(true);
    }

    /**
     * Обработка запроса стандартной реконсиляции.
     *
     * @param event событие запроса reconciliation
     */
    @EventListener
    public void onStandardRecon(SystemEvents.StandardReconciliationRequestedEvent event) {
        log.info("[RECON] Handling Standard Reconciliation event.");
        reconcileAll(false);
    }

    /**
     * Периодическая реконсиляция системы (фоновой процесс).
     */
    @Scheduled(fixedDelay = 3600000)
    public void reconcileAll() {
        reconcileAll(false);
    }

    /**
     * Выполняет полную реконсиляцию системы.
     *
     * @param force принудительное выполнение без ограничений по времени
     */
    public void reconcileAll(boolean force) {
        reconcileOutbox();
        reconcilePendingOrders();
        reconcileBalances(force);
    }

    /**
     * Реконсиляция балансов между биржей и внутренним состоянием.
     *
     * <p>При критических расхождениях система:
     * <ul>
     *     <li>синхронизирует баланс</li>
     *     <li>пересобирает позиции</li>
     *     <li>может инициировать emergency stop</li>
     * </ul>
     *
     * @param force принудительная проверка
     */
    public void reconcileBalances(boolean force) {
        try {
            BigDecimal exchangeBalance = exchangeQueryService.getAvailableBalance("USDT");
            BigDecimal internalBalance = riskEngine.getState().availableBalance();

            boolean isColdStart = stateManager.isColdStart() ||
                    (stateManager.getState() == SystemStateManager.SystemState.COLD_START_RECONCILIATION);

            if (isColdStart) {
                log.info("[BOOTSTRAP_SYNC] Cold start reconciliation active. Force syncing internal balance: {} -> {}",
                        internalBalance, exchangeBalance);
                riskEngine.syncBalance(exchangeBalance);
                positionRebuildService.rebuildAllPositions();
                lastReconcileTimestamp = Instant.now();
                return;
            }

            if (internalBalance.signum() == 0 && exchangeBalance.signum() == 0) return;

            BigDecimal diff = exchangeBalance.subtract(internalBalance).abs();
            if (diff.signum() == 0) return;

            BigDecimal driftPercent = internalBalance.signum() != 0
                    ? diff.divide(internalBalance, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ONE;

            Instant now = Instant.now();
            if (!force && now.isBefore(lastReconcileTimestamp.plus(DRIFT_DETECTION_WINDOW))) {
                return;
            }

            if (driftPercent.compareTo(DRIFT_THRESHOLD) > 0) {
                log.error("[RECON-CRITICAL] CRITICAL balance drift detected: Drift={}%",
                        driftPercent.multiply(new BigDecimal("100")));

                riskEngine.syncBalance(exchangeBalance);
                positionRebuildService.rebuildAllPositions();

                if (stateManager.isReady() && !isColdStart) {
                    notifications.sendCritical("Trading HALTED: Balance drift exceeds 1%.");
                    riskEngine.emergencyStop("Critical balance drift: " + driftPercent);
                }
            } else {
                log.info("[RECON] Minor drift detected. Auto-repairing state.");
                riskEngine.syncBalance(exchangeBalance);
                positionRebuildService.rebuildAllPositions();
            }

            lastReconcileTimestamp = now;

        } catch (Exception e) {
            log.error("[RECON] Failed to reconcile balances", e);
        }
    }

    /**
     * Реконсиляция outbox событий (очистка застрявших сообщений).
     */
    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void reconcileOutbox() {
        Instant threshold = Instant.now().minusSeconds(30);
        List<OutboxEventEntity> stuckEvents = outboxRepository.findStaleProcessingEvents(threshold);

        if (!stuckEvents.isEmpty()) {
            log.warn("[RECON] Found {} stuck outbox events. Resetting to FAILED.", stuckEvents.size());
            stuckEvents.forEach(event -> {
                event.setStatus(OutboxStatus.FAILED);
                event.setUpdatedAt(Instant.now());
            });
            outboxRepository.saveAll(stuckEvents);
        }
    }

    /**
     * Реконсиляция ордеров в промежуточных состояниях.
     */
    @Scheduled(fixedDelay = 300000)
    public void reconcilePendingOrders() {
        Instant threshold = Instant.now().minus(STALE_THRESHOLD);
        Set<OrderStatus> reconcilableStatuses = transitionValidator.getReconcilableStatuses();

        List<Order> stuckOrders = orderRepository.findStuckOrdersInStatuses(reconcilableStatuses, threshold);

        for (Order order : stuckOrders) {
            ExecutionContext context = ExecutionContext.of(order);
            syncOrderWithExchange(order, context);
        }
    }

    /**
     * Реконсиляция конкретного ордера.
     *
     * @param context контекст исполнения
     */
    @Transactional
    public void reconcile(ExecutionContext context) {
        orderRepository.findById(UUID.fromString(context.business().orderId()))
                .ifPresent(order -> syncOrderWithExchange(order, context));
    }

    /**
     * Синхронизация состояния ордера с биржей.
     *
     * <p>Является ключевой точкой обеспечения консистентности между доменной моделью и биржей.
     *
     * @param targetOrder целевой ордер
     * @param context контекст исполнения
     */
    @Transactional
    public void syncOrderWithExchange(Order targetOrder, ExecutionContext context) {
        try {
            Optional<Order> orderOpt = orderRepository.claimForReconciliation(targetOrder.getId());

            if (orderOpt.isEmpty()) {
                log.debug("[RECON-SKIP] Order {} is currently executing or terminal.", targetOrder.getId());
                return;
            }

            Order order = orderOpt.get();

            if (order.getStatus() == OrderStatus.UNKNOWN) {
                order.markRecovering(context);
                orderRepository.save(order);
            }

            executionLogger.log(ExecutionLogFactory.from(
                    order,
                    context,
                    ExecutionEventType.RECON_START,
                    ExecutionStateMapper.toContractState(order.getStatus()),
                    "Reconciling order " + order.getId()
            ));

            log.info("[RECON] Syncing order {} (status: {}).", order.getId(), order.getStatus());

            ExecutionResult exchangeState = exchangeQueryService.getOrderStatus(order.getClientOrderId());

            BinanceStatusMapper.Action action =
                    BinanceStatusMapper.mapToReconciliationAction(exchangeState.getStatus());

            boolean stateChanged = switch (action) {
                case FORCE_FILL -> {
                    order.forceFill(context,
                            exchangeState.getExchangeOrderId(),
                            exchangeState.getExecutedQty(),
                            exchangeState.getExecutedPrice());
                    yield true;
                }
                case PARTIALLY_FILL -> {
                    order.applyPartialFill(context,
                            exchangeState.getExecutedQty(),
                            exchangeState.getExecutedPrice());
                    yield true;
                }
                case REJECT -> {
                    order.markAsRejected(context, exchangeState.getErrorMessage());
                    orderCompensationService.releasePartial(order, order.getExecutedQuantity());
                    yield true;
                }
                case CANCEL -> {
                    order.markCancelled(context);
                    orderCompensationService.releasePartial(order, order.getExecutedQuantity());
                    yield true;
                }
                case MARK_UNKNOWN -> {
                    order.markAsUnknown(context);
                    yield true;
                }
                case NOOP -> false;

                case FILL, MARK_ACCEPTED -> {
                    order.markAsUnknown(context);
                    yield true;
                }
            };

            if (stateChanged) {
                orderRepository.save(order);
            }

        } catch (OptimisticLockException e) {
            log.warn("[RECON-CONFLICT] Stale version for order {}. Skipping.", targetOrder.getId());
        } catch (Exception e) {
            log.error("[RECON-ERROR] Failed to sync order {}", targetOrder.getId(), e);
        }
    }
}