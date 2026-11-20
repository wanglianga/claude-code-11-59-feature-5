package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/**
 * 外部业务系统只读引用（缺陷观测/检查覆盖/维修记录）。
 * 平台只保存引用及来源版本：不重新定位缺陷、不裁决观测、不推导覆盖与维修状态。
 */
public record ExternalRef(
        long id,
        String refType,
        String externalId,
        String sourceSystem,
        String sourceVersion,
        Long segmentId,
        OffsetDateTime observedAt,
        String payloadJson,
        OffsetDateTime registeredAt
) {}
