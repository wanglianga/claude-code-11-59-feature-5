package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/**
 * 运行观测。sampled_at 为采样时间，ingested_at 为入库时间；
 * 迟到补录 ingested_at 晚于 sampled_at，历史复算按 ingested_at <= as_of 判定可见性。
 */
public record Observation(
        long id,
        String obsType,
        String targetType,
        String targetCode,
        Double valueNum,
        String valueText,
        String unit,
        OffsetDateTime sampledAt,
        OffsetDateTime ingestedAt,
        String source,
        String sourceVersion,
        String calibrationStatus
) {}
