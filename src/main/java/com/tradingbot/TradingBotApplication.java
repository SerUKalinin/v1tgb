package com.tradingbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.retry.annotation.EnableRetry;

/**
 * Главный класс приложения Trading Bot.
 */
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
}