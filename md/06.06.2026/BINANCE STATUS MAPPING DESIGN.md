1. Все существующие места маппинга
   Mapping-site #1: BinanceExecutionAdapter.mapToExecutionResult()
   Файл: BinanceExecutionAdapter.java, строки 151–165 Путь: placeOrder() → ответ POST /api/v3/order

boolean success = "FILLED".equals(status) || "NEW".equals(status) || "PARTIALLY_FILLED".equals(status);
// success → ExecutionResult.Status.SUCCESS
// !success → ExecutionResult.Status.REJECTED (вызов ExecutionResult.rejected())
Binance Статус	→	ExecutionResult.Status
NEW	→	SUCCESS
PARTIALLY_FILLED	→	SUCCESS
FILLED	→	SUCCESS
всё остальное	→	REJECTED
Mapping-site #2: BinanceExecutionAdapter.mapStatusResponse()
Файл: BinanceExecutionAdapter.java, строки 178–186 Путь: getOrderStatus() → GET /api/v3/order → recovery/watchdog/recon

boolean success = "FILLED".equals(response.getStatus());
// success → ExecutionResult.Status.SUCCESS
// !success → ExecutionResult.Status.REJECTED
Binance Статус	→	ExecutionResult.Status
FILLED	→	SUCCESS
всё остальное	→	REJECTED
Mapping-site #3: BinanceExecutionAdapter.mapErrorToResult()
Файл: BinanceExecutionAdapter.java, строки 167–176 Путь: исключения при вызове API

if (msg.contains("400") || msg.contains("-1013") || msg.contains("-1111")) → REJECTED
if (msg.contains("Timeout") || msg.contains("504")) → TIMEOUT
else → FAILED_IO
Mapping-site #4: OrderExecutionHandler.commitExecution()
Файл: OrderExecutionHandler.java, строки 171–189 Путь: ExecutionResult.Status → OrderStatus

SUCCESS   → order.fill()           → FILLED
REJECTED  → order.markAsRejected() → REJECTED
TIMEOUT   → order.markAsUnknown()  → UNKNOWN
FAILED_IO → log.error (без перехода)
CANCELED  → log.info  (без перехода)
Mapping-site #5: OrderStateTransitionPolicy.mapExecutionResult()
Файл: OrderStateTransitionPolicy.java, строки 118–126 Путь: вспомогательный mapper (не используется в горячем пути)

SUCCESS  → FILLED
REJECTED → REJECTED
CANCELED → CANCELED
TIMEOUT  → UNKNOWN
default  → null
Mapping-site #6: ReconciliationService.syncOrderWithExchange()
Файл: ReconciliationService.java, строки 202–219 Путь: ExecutionResult.Status → OrderStatus (через методы Order)

SUCCESS  → order.forceFill()       → FILLED
REJECTED → order.markAsRejected()  → REJECTED (+ release capital)
CANCELED → order.markCancelled()   → CANCELED (+ release capital)
Сводка: 6 мест, 4 разных маппинга
Статус Binance	MS#1 (placeOrder)	MS#2 (getOrderStatus)	MS#3 (error)
NEW	SUCCESS	REJECTED	—
PARTIALLY_FILLED	SUCCESS	REJECTED	—
FILLED	SUCCESS	SUCCESS	—
CANCELED	REJECTED	REJECTED	—
REJECTED	REJECTED	REJECTED	—
EXPIRED	REJECTED	REJECTED	—
PENDING_CANCEL	REJECTED	REJECTED	—
ошибка 400/-1013	—	—	REJECTED
ошибка Timeout/504	—	—	TIMEOUT
остальные ошибки	—	—	FAILED_IO
2. Что должно быть удалено
   ❌ Удалить: MS#1 и MS#2 как отдельные маппинги
   Оба должны вызывать единый mapper. Разная логика для placeOrder и getOrderStatus — корень всех проблем.

❌ Удалить: MS#4 и MS#5 — inline switch/logic в commitExecution
Вся логика «ExecutionResult.Status → что делать с ордером» должна быть в едином mapper-классе.

❌ Удалить: OrderStateTransitionPolicy.mapExecutionResult()
Этот метод устаревает — новый маппер возьмёт на себя всю цепочку.

3. Единый mapper: BinanceStatusMapper
   3A. Расширенный ExecutionResult.Status
   public enum Status {
   // === Терминальные: исполнение завершено ===
   FILLED,              // Полностью исполнен
   REJECTED,            // Отклонён биржей (окончательно)
   CANCELED,            // Отменён

   // === Промежуточные: ордер живёт на бирже ===
   ACCEPTED,            // Принят биржей, 0 fill (Binance: NEW)
   PARTIALLY_FILLED,    // Частично исполнен (Binance: PARTIALLY_FILLED)

   // === Неопределённость ===
   EXCHANGE_STATE_UNKNOWN,  // Состояние неизвестно (timeout / IO error)

   // Удалённые: SUCCESS, TIMEOUT, FAILED_IO
   }
   3B. Изменения в Order (новый метод)
   // Order.java — новый метод
   public void markAccepted(ExecutionContext context, String exchangeOrderId) {
   OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.SENT_TO_EXCHANGE);
   this.exchangeOrderId = exchangeOrderId;
   this.status = OrderStatus.SENT_TO_EXCHANGE;
   }
   3C. Единый маппер
   package com.tradingbot.infrastructure.binance;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.ExecutionResult;

/**
* ЕДИНЫЙ ИСТОЧНИК ИСТИНЫ для маппинга Binance статусов.
*
* Инварианты:
* - FILLED            → терминальное, fill данные гарантированы
* - PARTIALLY_FILLED  → промежуточное, fill данные гарантированы
* - ACCEPTED          → промежуточное, fill данных НЕТ (executedQty = 0)
* - REJECTED/CANCELED → терминальное, fill данных НЕТ
    */
    public final class BinanceStatusMapper {

private BinanceStatusMapper() {}

/**
    * Маппинг Binance API статуса → ExecutionResult.Status.
    * Единый для placeOrder и getOrderStatus.
      */
      public static ExecutionResult.Status mapBinanceStatus(String binanceStatus) {
      return switch (binanceStatus) {
      case "NEW"               -> ExecutionResult.Status.ACCEPTED;
      case "PARTIALLY_FILLED"  -> ExecutionResult.Status.PARTIALLY_FILLED;
      case "FILLED"            -> ExecutionResult.Status.FILLED;
      case "CANCELED",
      "PENDING_CANCEL"    -> ExecutionResult.Status.CANCELED;
      case "REJECTED",
      "EXPIRED"           -> ExecutionResult.Status.REJECTED;
      default                  -> ExecutionResult.Status.REJECTED;
      };
      }

/**
    * ExecutionResult.Status → OrderStatus (следующий шаг в жизненном цикле).
      */
      public static OrderStatus mapToOrderStatus(ExecutionResult.Status resultStatus) {
      return switch (resultStatus) {
      case FILLED             -> OrderStatus.FILLED;
      case PARTIALLY_FILLED   -> OrderStatus.PARTIALLY_FILLED;
      case ACCEPTED           -> OrderStatus.SENT_TO_EXCHANGE;
      case REJECTED           -> OrderStatus.REJECTED;
      case CANCELED           -> OrderStatus.CANCELED;
      case EXCHANGE_STATE_UNKNOWN -> OrderStatus.UNKNOWN;
      };
      }

/**
    * ExecutionResult.Status → действие над Order.
    * Вызывается из commitExecution() и ReconciliationService.
      */
      public static Action mapToAction(ExecutionResult.Status status) {
      return switch (status) {
      case FILLED             -> Action.FILL;
      case PARTIALLY_FILLED   -> Action.PARTIAL_FILL;
      case ACCEPTED           -> Action.ACCEPT;
      case REJECTED           -> Action.REJECT;
      case CANCELED           -> Action.CANCEL;
      case EXCHANGE_STATE_UNKNOWN -> Action.MARK_UNKNOWN;
      };
      }

public enum Action {
FILL,           // order.fill(context, exchangeOrderId, executedQty, executedPrice)
PARTIAL_FILL,   // order.applyPartialFill(context, executedQty, executedPrice)
ACCEPT,         // order.markAccepted(context, exchangeOrderId)
REJECT,         // order.markAsRejected(context, reason) + riskEngine.release()
CANCEL,         // order.markCancelled(context) + riskEngine.release()
MARK_UNKNOWN,   // order.markAsUnknown(context)
}

/**
    * Маппинг исключений API → ExecutionResult.Status.
      */
      public static ExecutionResult.Status mapError(Exception e) {
      String msg = e.getMessage();
      if (msg == null) return ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN;

      if (msg.contains("400") || msg.contains("-1013") || msg.contains("-1111")) {
      return ExecutionResult.Status.REJECTED;
      }
      return ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN;
      // Примечание: retry-логика (exponential backoff) управляется
      // @Retry аннотацией, а не статусом. EXCHANGE_STATE_UNKNOWN
      // означает "попробуй позже через reconciliation".
      }
      }
      3D. Обновлённые commitExecution() и ReconciliationService
      // OrderExecutionHandler.commitExecution() — упрощённая версия
      public void commitExecution(..., ExecutionResult result, ...) {
      BinanceStatusMapper.Action action = BinanceStatusMapper.mapToAction(result.getStatus());

switch (action) {
case FILL -> order.fill(context,
result.getExchangeOrderId(),
result.getExecutedQty(),
result.getExecutedPrice());
case PARTIAL_FILL -> order.applyPartialFill(context,
result.getExecutedQty(),
result.getExecutedPrice());
case ACCEPT -> order.markAccepted(context,
result.getExchangeOrderId());
case REJECT -> {
order.markAsRejected(context, result.getErrorMessage());
riskEngine.release(context, order.getQuantity(), "Rejected");
}
case CANCEL -> {
order.markCancelled(context);
riskEngine.release(context, order.getQuantity(), "Cancelled");
}
case MARK_UNKNOWN -> order.markAsUnknown(context);
}
orderRepository.save(order);
}
// ReconciliationService.syncOrderWithExchange() — упрощённая версия
ExecutionResult exchangeState = exchangeQueryService.getOrderStatus(order.getClientOrderId());
BinanceStatusMapper.Action action = BinanceStatusMapper.mapToAction(exchangeState.getStatus());

switch (action) {
case FILL -> order.forceFill(context,
exchangeState.getExchangeOrderId(),
exchangeState.getExecutedQty(),
exchangeState.getExecutedPrice());
case PARTIAL_FILL -> order.applyPartialFill(context,
exchangeState.getExecutedQty(),
exchangeState.getExecutedPrice());
case ACCEPT -> {
// Ордер жив, ничего не делаем — ждём следующего reconciliation цикла
log.info("[RECON] Order {} is still OPEN on exchange. Waiting.", order.getId());
return; // ← без stateChanged, без save
}
case REJECT -> {
order.markAsRejected(context, exchangeState.getErrorMessage());
riskEngine.release(context, order.getQuantity(), "Reconciliation rejection");
}
case CANCEL -> {
order.markCancelled(context);
riskEngine.release(context, order.getQuantity(), "Reconciliation cancelled");
}
case MARK_UNKNOWN -> order.markAsUnknown(context);
}
4. Итоговый маппинг для каждого Binance статуса
   NEW — Ордер принят биржей, 0 исполнено
   Шаг	Значение
   Binance status	NEW
   ExecutionResult.Status	ACCEPTED (новый статус)
   executedQty	BigDecimal("0")
   executedPrice	null
   Action	ACCEPT
   OrderStatus	SENT_TO_EXCHANGE
   Wait for	fill, cancel, или reconciliation
   PARTIALLY_FILLED — Частичное исполнение
   Шаг	Значение
   Binance status	PARTIALLY_FILLED
   ExecutionResult.Status	PARTIALLY_FILLED (новый статус)
   executedQty	response.get("executedQty") — фактический fill
   executedPrice	response.get("price") или cummulativeQuoteQty / executedQty
   Action	PARTIAL_FILL
   OrderStatus	PARTIALLY_FILLED
   Wait for	fill остатка, cancel остатка, или reconciliation
   FILLED — Полное исполнение
   Шаг	Значение
   Binance status	FILLED
   ExecutionResult.Status	FILLED (переименован из SUCCESS)
   executedQty	response.get("executedQty") — полный fill
   executedPrice	response.get("price") или рассчитанная средняя
   Action	FILL
   OrderStatus	FILLED
   Terminal	✅ Да
   CANCELED — Отменён
   Шаг	Значение
   Binance status	CANCELED
   ExecutionResult.Status	CANCELED
   executedQty	null (или фактический если был частичный fill до отмены)
   executedPrice	null
   Action	CANCEL
   OrderStatus	CANCELED
   Terminal	✅ Да
   Capital	Освободить остаток через OrderCompensationService.releasePartial()
   REJECTED — Отклонён биржей
   Шаг	Значение
   Binance status	REJECTED
   ExecutionResult.Status	REJECTED
   executedQty	null
   executedPrice	null
   Action	REJECT
   OrderStatus	REJECTED
   Terminal	✅ Да
   Capital	Освободить полностью
   EXPIRED — Истёк срок действия
   Шаг	Значение
   Binance status	EXPIRED
   ExecutionResult.Status	REJECTED
   Action	REJECT
   OrderStatus	REJECTED
   Terminal	✅ Да
   Capital	Освободить полностью
   Примечание	Binance возвращает EXPIRED для лимитных ордеров с timeInForce. Семантически эквивалентно REJECTED/CANCELED.
   PENDING_CANCEL — В процессе отмены
   Шаг	Значение
   Binance status	PENDING_CANCEL
   ExecutionResult.Status	CANCELED (презумпция: отмена произойдёт)
   Action	CANCEL
   OrderStatus	CANCELED
   Terminal	✅ Да
   Примечание	Если отмена не прошла — reconciliation вернёт реальный статус.
   Ошибка I/O при вызове API
   Шаг	Значение
   Исключение	IOException, SocketTimeoutException, etc.
   ExecutionResult.Status	EXCHANGE_STATE_UNKNOWN
   Action	MARK_UNKNOWN
   OrderStatus	UNKNOWN
   Terminal	❌ Нет
   Recovery	Через watchdog/reconciliation
5. Финальная таблица переходов
#	Binance Status	ExecutionResult.Status	Action	OrderStatus	Terminal	Capital
1	NEW	ACCEPTED	ACCEPT	SENT_TO_EXCHANGE	❌	—
2	PARTIALLY_FILLED	PARTIALLY_FILLED	PARTIAL_FILL	PARTIALLY_FILLED	❌	—
3	FILLED	FILLED	FILL	FILLED	✅	—
4	CANCELED	CANCELED	CANCEL	CANCELED	✅	release partial
5	REJECTED	REJECTED	REJECT	REJECTED	✅	release full
6	EXPIRED	REJECTED	REJECT	REJECTED	✅	release full
7	PENDING_CANCEL	CANCELED	CANCEL	CANCELED	✅	release partial
8	API error	EXCHANGE_STATE_UNKNOWN	MARK_UNKNOWN	UNKNOWN	❌	—
Граф состояний с новым маппингом
┌──────────────┐
│ PENDING_EXEC │
└──────┬───────┘
│ claim
▼
┌──────────┐
│ EXECUTING│
└────┬─────┘
│ placeOrder → Binance
▼
┌──────────────────────┐
│   SENT_TO_EXCHANGE   │ ← NEW (ACCEPTED)
└──────┬───────────────┘
│
┌──────────┼──────────┬──────────┐
▼          ▼          ▼          ▼
┌──────────┐ ┌────────┐ ┌──────┐ ┌──────────┐
│PARTIALLY │ │ FILLED │ │REJECT│ │ CANCELED │
│_FILLED   │ │   ✅   │ │  ✅  │ │    ✅    │
└────┬─────┘ └────────┘ └──────┘ └──────────┘
│
┌────┴────┐
▼         ▼
┌──────┐ ┌────────┐
│FILLED│ │CANCELED│
│  ✅  │ │   ✅   │
└──────┘ └────────┘
Сводка изменений ExecutionResult.Status
Текущий	Целевой	Причина
SUCCESS	FILLED	Переименование — точное имя
SUCCESS	ACCEPTED	Разделение — NEW≠FILLED
SUCCESS	PARTIALLY_FILLED	Разделение — partial≠full
TIMEOUT	EXCHANGE_STATE_UNKNOWN	Объединение с FAILED_IO
FAILED_IO	EXCHANGE_STATE_UNKNOWN	Объединение с TIMEOUT
REJECTED	REJECTED	Без изменений
CANCELED	CANCELED	Без изменений
Принципы дизайна
Единственный источник маппинга: BinanceStatusMapper.mapBinanceStatus() — один метод для placeOrder и getOrderStatus.

Каждый Binance статус → уникальный ExecutionResult.Status: нет коллизий (NEW ≠ FILLED, PARTIALLY_FILLED ≠ SUCCESS).

ExecutionResult.Status → детерминированное Action: никаких if/else в commitExecution — чистый switch по Action enum.

EXCHANGE_STATE_UNKNOWN заменяет TIMEOUT и FAILED_IO: единый статус для «не знаем что там» → требует reconciliation, а не немедленного решения.

ACCEPTED не освобождает капитал: ордер живёт на бирже, капитал зарезервирован.