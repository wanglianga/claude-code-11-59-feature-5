package com.citydrain.capacity.domain;

/**
 * 复检计划明细：入选/落选候选及原因。
 * 落选原因区分预算竞争、供应商不可达、雨季禁入、低价值、已维修完整验收覆盖。
 */
public record ReinspectionPlanItem(
        long id,
        long planId,
        long candidateId,
        long segmentId,
        boolean selected,
        double evi,
        double cost,
        double expectedRiskReductionM,
        double decisionChangeProb,
        String reason,
        String detail
) {}
