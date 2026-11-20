package com.citydrain.capacity.domain;

/**
 * 单个候选复检的信息价值估计结果。两路线产出同一口径 evi（[0,1] 预期信息价值）：
 * - 枚举法：枚举“发现更严重/确认现状/发现已修复”等离散结果，重算受影响处置决定并按结果概率加权；
 * - 敏感度法：风险/影响模型在观测不确定度上的局部敏感度近似，适合大批候选筛选。
 * 同时输出对整治排序、剩余风险长度、网络影响结论可能缩小的范围与低价值标记。
 */
public record VoiEstimationResult(
        long id,
        long estimationId,
        long candidateId,
        long segmentId,
        double evi,
        int eviRank,
        double decisionChangeProb,
        boolean lowValue,
        String lowValueReasonsJson,
        double resolutionPenalty,
        boolean excludedRepaired,
        String outcomeBreakdownJson,
        String sensitivityDetailJson,
        double expectedRiskReductionM,
        String riskLengthNarrowingJson,
        String networkConclusionNarrowingJson
) {}
