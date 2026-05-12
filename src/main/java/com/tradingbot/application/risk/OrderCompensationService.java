package com.tradingbot.application.risk;

import com.tradingbot.domain.model.Order;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCompensationService {

    private final RiskEngine riskEngine;

    public void releasePartial(Order order, BigDecimal executedQty) {

        BigDecimal remainingQty = order.getRemainingQuantity();

        if (remainingQty.compareTo(BigDecimal.ZERO) > 0) {

            BigDecimal releaseAmount = remainingQty.multiply(order.getPrice());

            riskEngine.release(
                    IdentityContext.of(order.getSignalId()),
                    ExecutionAttemptContext.recover(order.getSignalId()),
                    BusinessContext.of(order.getId().toString()),
                    releaseAmount,
                    "Partial fill compensation"
            );
        }
    }

    public void releaseFull(Order order, String reason) {

        BigDecimal releaseAmount =
                order.getQuantity().multiply(order.getPrice());

        riskEngine.release(
                IdentityContext.of(order.getSignalId()),
                ExecutionAttemptContext.recover(order.getSignalId()),
                BusinessContext.of(order.getId().toString()),
                releaseAmount,
                reason
        );
    }
}