package com.tradingbot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("backtest")
class TradingBotApplicationTests {
	@Test
	void contextLoads() {
	}

}
