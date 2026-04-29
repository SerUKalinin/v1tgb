package com.tradingbot.fakes;

import com.tradingbot.infrastructure.telegram.TradingTelegramBot;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class FakeTelegramBot extends TradingTelegramBot {
    public FakeTelegramBot() {
        super(null, null, null);
    }

    @Override
    public void sendMessage(Long chatId, String text) {
        // Do nothing in tests
    }

    public void sendCriticalAlert(String message) {
        // Do nothing in tests
    }
}
