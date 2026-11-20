package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/** 水文事件：同一事件的观测组合边界；窗口外/未对齐的峰值不得合成进本事件。 */
public record HydroEvent(
        long id,
        String eventCode,
        String name,
        OffsetDateTime windowStart,
        OffsetDateTime windowEnd,
        OffsetDateTime createdAt
) {}
