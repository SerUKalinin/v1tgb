package com.tradingbot.application.service;

import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestService {

    private final PositionRepository repository;

    @PostConstruct
    public void test() {
        PositionEntity p = new PositionEntity();
        p.setSymbol("TEST");
        p.setQuantity(BigDecimal.valueOf(1.0));
        p.setEntryPrice(BigDecimal.valueOf(100.0));

        repository.save(p);

        log.info("Saved test position");

        var all = repository.findAll();
        log.info("Positions in DB: {}", all.size());
    }
}