package com.tradingbot.domain.execution;

import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;

public interface ExecutionClaimPort {

    void claim(
            IdentityContext identity,
            ExecutionAttemptContext attempt,
            BusinessContext business
    );
}