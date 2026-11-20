package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/**
 * 资产参数版本：经确认的几何/坡度/糙率/淤积测量区间，带有效时间窗。
 * 区间字段为 null 表示该参数缺失（计算时扩大区间并标记限制来源）。
 */
public record ParamVersion(
        long id,
        long segmentId,
        int versionNo,
        String source,
        Double diameterMinMm,
        Double diameterMaxMm,
        Double slopeMin,
        Double slopeMax,
        Double roughnessMin,
        Double roughnessMax,
        Double siltRatioMin,
        Double siltRatioMax,
        OffsetDateTime validFrom,
        OffsetDateTime validTo,
        OffsetDateTime createdAt,
        String createdBy,
        Long repairRefId,
        String note
) {}
