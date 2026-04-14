package com.tradingbot.application.service;

import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class EquityService {

    private final TradeRepository tradeRepository;

    public BigDecimal calculateTotalRealizedPnL() {

        List<TradeEntity> trades = tradeRepository.findAll();

        BigDecimal pnl = trades.stream()
                .map(t -> {
                    BigDecimal sign = t.getSide().name().equals("BUY")
                            ? BigDecimal.valueOf(-1)
                            : BigDecimal.valueOf(1);

                    return t.getPrice()
                            .multiply(t.getQuantity())
                            .multiply(sign);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return pnl.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateVolume(String symbol) {

        return tradeRepository.findAll().stream()
                .filter(t -> t.getSymbol().equals(symbol))
                .map(t -> t.getPrice().multiply(t.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}