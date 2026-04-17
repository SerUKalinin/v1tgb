package com.tradingbot;

import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;
import java.util.Map;

/**
 * Главный класс приложения Trading Bot.
 */
@Slf4j
@SpringBootApplication
@EnableScheduling
@EnableRetry
public class TradingBotApplication {
	/**
	 * Точка входа в приложение.
	 *
	 * @param args аргументы командной строки
	 */
	public static void main(String[] args) {
		SpringApplication.run(TradingBotApplication.class, args);
	}

	@Bean
	@Profile("testnet")
	public CommandLineRunner checkBalance(BinanceClient binanceClient) {
		return args -> {
			try {
				log.info("[BALANCE] Checking Binance Testnet balances...");
				Map accountInfo = binanceClient.getAccountInfo();
				if (accountInfo != null && accountInfo.containsKey("balances")) {
					List<Map> balances = (List<Map>) accountInfo.get("balances");
					balances.stream()
							.filter(b -> Double.parseDouble(b.get("free").toString()) > 0)
							.forEach(b -> log.info("[BALANCE] {} : {}", b.get("asset"), b.get("free")));
				}
			} catch (Exception e) {
				log.error("[BALANCE] Failed to fetch balance: {}", e.getMessage());
			}
		};
	}
}