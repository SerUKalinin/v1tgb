package com.tradingbot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Конфигурация планировщика задач.
 *
 * <p>Scheduled-задачи работают только вне test profile.
 * Integration tests должны управлять lifecycle явно и не запускать
 * фоновые reconciliation/watchdog процессы параллельно с test setup.</p>
 */
@Configuration
@Profile("!test")
@EnableScheduling
public class SchedulerConfig implements SchedulingConfigurer {

    /**
     * Настраивает thread pool для выполнения scheduled-задач системы.
     *
     * @param taskRegistrar регистратор scheduled-задач
     */
    @Override
    public void configureTasks(
            ScheduledTaskRegistrar taskRegistrar
    ) {
        ThreadPoolTaskScheduler scheduler =
                new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix(
                "trading-scheduler-"
        );
        scheduler.initialize();

        taskRegistrar.setTaskScheduler(
                scheduler
        );
    }
}