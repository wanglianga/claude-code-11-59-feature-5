package com.citydrain.capacity.domain;

/** 能力评估结果（每段每运行一条）。 */
public record CapacityResult(
        long id,
        long runId,
        long segmentId,
        Double qMinLps,
        Double qMaxLps,
        Double marginMin,
        Double marginMax,
        String dominantUncertaintyJson,
        String limitationsJson,
        String undeterminableReason,
        String evidenceJson
) {}
