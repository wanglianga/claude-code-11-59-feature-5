package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/**
 * 复检候选方案：可覆盖位置、方法分辨率、预计定位误差、成本、可发现缺陷类别、
 * 供应商可达性与雨季禁入。由外部检测能力登记，本平台据此估计复检信息价值。
 */
public record ReinspectionCandidate(
        long id,
        String candidateCode,
        long segmentId,
        String methodCode,
        double methodResolutionMm,
        double locationErrorM,
        double cost,
        String detectableCategoriesJson,
        String locationRangeJson,
        boolean supplierReachable,
        boolean rainySeasonBlocked,
        String status,
        String note,
        OffsetDateTime createdAt
) {}
