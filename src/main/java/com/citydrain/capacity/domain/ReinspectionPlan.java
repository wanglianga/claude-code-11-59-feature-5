package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/**
 * 复检计划版本：预算/供应商可达性/雨季禁入约束下的组合选择结果。
 * 约束变化后生成新版本（supersedes_plan_id 指向前序版本），旧计划与约束快照保留，不更新不删除。
 */
public record ReinspectionPlan(
        long id,
        String planCode,
        int planVersion,
        long estimationId,
        double budget,
        boolean supplierOnlyReachable,
        String rainySeasonWindowJson,
        double totalCost,
        double totalEvi,
        String constraintSnapshotJson,
        Long supersedesPlanId,
        OffsetDateTime createdAt
) {}
