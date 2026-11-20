package com.citydrain.capacity.domain;

/**
 * 状况评估版本内的位置条目：风险区间、证据冲突、定位模糊范围与当前处置决定。
 * currentDecision 取自外部评估（REMEDIATE / MONITOR / NO_ACTION），本平台不自行裁决。
 */
public record ConditionPositionEntry(
        long id,
        long assessmentId,
        long segmentId,
        String locationRangeJson,
        double riskScoreMin,
        double riskScoreMax,
        boolean evidenceConflict,
        double locationAmbiguityM,
        String currentDecision,
        double currentRiskLengthM,
        String note
) {}
