package com.tradingbot.tracing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultExecutionLogger implements ExecutionLogger {

    @Override
    public void log(ExecutionLogRecord record) {
        if (record.executionId() == null || record.signalId() == null) {
            log.error("[LOGGING-RULE-VIOLATION] Attempted to log partial identity: {}", record);
            return;
        }
        
        log.info("[EXECUTION-TRACE] signalId={} correlationId={} executionId={} causationId={} orderId={} event={} state={} message={}",
                record.signalId(),
                record.correlationId(),
                record.executionId(),
                record.causationId(),
                record.orderId(),
                record.eventType(),
                record.state(),
                record.message()
        );
    }
}
