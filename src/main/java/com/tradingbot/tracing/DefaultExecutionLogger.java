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
        if (record == null) {
            return;
        }
        log.info("[EXECUTION] executionId={} orderId={} signalId={} event={} state={} message={}",
                record.executionId(),
                record.orderId(),
                record.signalId(),
                record.event(),
                record.state(),
                record.message());
    }
}
