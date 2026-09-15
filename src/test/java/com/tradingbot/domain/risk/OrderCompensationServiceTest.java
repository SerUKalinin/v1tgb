package com.tradingbot.domain.risk;

import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.model.Order;
import com.tradingbot.tracing.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты OrderCompensationService.
 *
 * <p>Проверяют:
 * <ul>
 *     <li>BUY -> consume reservation;</li>
 *     <li>SELL -> CapitalCredited;</li>
 *     <li>SELL proceeds рассчитываются по фактическим execution data;</li>
 *     <li>SELL без executed quantity отклоняется;</li>
 *     <li>SELL без executed price отклоняется.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderCompensationServiceTest {

    @Mock
    private RiskEngine riskEngine;

    @InjectMocks
    private OrderCompensationService compensationService;

    private Order createFilledOrder(
            OrderSide side,
            BigDecimal originalQuantity,
            BigDecimal orderPrice,
            BigDecimal executedQuantity,
            BigDecimal averagePrice
    ) {
        UUID orderId = UUID.randomUUID();

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "client-" + orderId,
                        "BTCUSDT",
                        side,
                        OrderType.MARKET,
                        originalQuantity,
                        orderPrice,
                        "SMA_STUB",
                        UUID.randomUUID()
                );

        ExecutionContext context =
                ExecutionContext.of(order);

        order.markExecuting(context);

        order.fill(
                context,
                "exchange-order-" + orderId,
                executedQuantity,
                averagePrice
        );

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.FILLED);

        return order;
    }

    @Test
    void shouldConsumeReservationForBuy() {
        Order order =
                createFilledOrder(
                        OrderSide.BUY,
                        new BigDecimal("0.00128"),
                        new BigDecimal("77642.01"),
                        new BigDecimal("0.00128"),
                        new BigDecimal("77642.01")
                );

        compensationService.consumeReservation(
                order,
                "Order fully filled"
        );

        ArgumentCaptor<ExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(
                        ExecutionContext.class
                );

        verify(riskEngine)
                .consumeReservation(
                        contextCaptor.capture(),
                        eq("Order fully filled")
                );

        ExecutionContext capturedContext =
                contextCaptor.getValue();

        assertThat(capturedContext)
                .isNotNull();

        assertThat(
                capturedContext.business().orderId()
        ).isEqualTo(
                order.getId().toString()
        );

        verify(riskEngine, never())
                .publish(any(RiskEvent.class));

        verifyNoMoreInteractions(riskEngine);
    }

    @Test
    void shouldPublishCapitalCreditedForSell() {
        Order order =
                createFilledOrder(
                        OrderSide.SELL,
                        new BigDecimal("0.00128"),
                        new BigDecimal("77600"),
                        new BigDecimal("0.00128"),
                        new BigDecimal("77618")
                );

        compensationService.consumeReservation(
                order,
                "Order fully filled"
        );

        ArgumentCaptor<RiskEvent> eventCaptor =
                ArgumentCaptor.forClass(
                        RiskEvent.class
                );

        verify(riskEngine)
                .publish(eventCaptor.capture());

        verify(riskEngine, never())
                .consumeReservation(
                        any(ExecutionContext.class),
                        anyString()
                );

        RiskEvent event =
                eventCaptor.getValue();

        assertThat(event)
                .isInstanceOf(
                        RiskEvent.CapitalCredited.class
                );

        RiskEvent.CapitalCredited credited =
                (RiskEvent.CapitalCredited) event;

        assertThat(credited.orderId())
                .isEqualTo(order.getId());

        assertThat(credited.amount())
                .isEqualByComparingTo(
                        new BigDecimal("99.35104")
                );

        assertThat(credited.reason())
                .isEqualTo(
                        "SELL order fully filled"
                );

        assertThat(credited.eventId())
                .isNotBlank();

        verifyNoMoreInteractions(riskEngine);
    }

    @Test
    void shouldUseActualExecutedQuantityAndAveragePriceForSellProceeds() {
        Order order =
                createFilledOrder(
                        OrderSide.SELL,
                        new BigDecimal("0.00200"),
                        new BigDecimal("80000"),
                        new BigDecimal("0.00125"),
                        new BigDecimal("77600")
                );

        compensationService.consumeReservation(
                order,
                "Order fully filled"
        );

        ArgumentCaptor<RiskEvent> eventCaptor =
                ArgumentCaptor.forClass(
                        RiskEvent.class
                );

        verify(riskEngine)
                .publish(eventCaptor.capture());

        RiskEvent.CapitalCredited credited =
                (RiskEvent.CapitalCredited)
                        eventCaptor.getValue();

        /*
         * 0.00125 * 77600 = 97.00000
         *
         * Проверяем, что используются именно
         * фактические execution data:
         *
         * executedQuantity = 0.00125
         * averagePrice     = 77600
         *
         * а не:
         *
         * originalQuantity = 0.00200
         * orderPrice       = 80000
         */
        assertThat(credited.amount())
                .isEqualByComparingTo(
                        new BigDecimal("97.00000")
                );
    }

    @Test
    void shouldRejectSellWithoutExecutedQuantity() {
        Order baseOrder =
                createFilledOrder(
                        OrderSide.SELL,
                        new BigDecimal("0.00128"),
                        new BigDecimal("77600"),
                        new BigDecimal("0.00128"),
                        new BigDecimal("77618")
                );

        Order order =
                spy(baseOrder);

        doReturn(null)
                .when(order)
                .getExecutedQuantity();

        assertThrows(
                IllegalStateException.class,
                () -> compensationService.consumeReservation(
                        order,
                        "Order fully filled"
                )
        );

        verifyNoInteractions(riskEngine);
    }

    @Test
    void shouldRejectSellWithoutExecutedPrice() {
        Order baseOrder =
                createFilledOrder(
                        OrderSide.SELL,
                        new BigDecimal("0.00128"),
                        new BigDecimal("77600"),
                        new BigDecimal("0.00128"),
                        new BigDecimal("77618")
                );

        Order order =
                spy(baseOrder);

        doReturn(null)
                .when(order)
                .getAveragePrice();

        assertThrows(
                IllegalStateException.class,
                () -> compensationService.consumeReservation(
                        order,
                        "Order fully filled"
                )
        );

        verifyNoInteractions(riskEngine);
    }
}