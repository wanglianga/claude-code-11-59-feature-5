package com.citydrain.capacity.domain;

/** 覆盖账本条目：记录本次计算中参数和观测的可用性。 */
public record CoverageEntry(
        long id,
        long runId,
        Long segmentId,
        String item,
        String status,
        String detail
) {}
