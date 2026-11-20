package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/** 计算运行：方法 + as_of 历史时点；同一 (scenario, method, as_of) 幂等复算。 */
public record ComputationRun(
        long id,
        String runCode,
        long scenarioId,
        String method,
        OffsetDateTime asOf,
        String status,
        String networkSummaryJson,
        OffsetDateTime createdAt
) {}
