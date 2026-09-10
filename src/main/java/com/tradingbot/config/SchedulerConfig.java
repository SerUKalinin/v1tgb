package com.tradingbot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Конфигурация планировщика задач.
 *
 * <p>Определяет пул потоков для выполнения всех {@code @Scheduled} задач системы.
 * Используется для контроля параллелизма фоновых процессов (reconciliation, cleanup и т.д.).</p>
 */
@Configuration
public class SchedulerConfig implements SchedulingConfigurer {

    /**
     * Настраивает thread pool для выполнения scheduled-задач.
     *
     * <p>Используется фиксированный пул потоков для предотвращения
     * неконтролируемого создания потоков при высокой нагрузке.</p>
     *
     * @param taskRegistrar регистратор scheduled задач
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("trading-scheduler-");
        scheduler.initialize();
        taskRegistrar.setTaskScheduler(scheduler);
    }
}