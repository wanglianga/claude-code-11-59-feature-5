package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 情景：同一水文事件 + 管网版本 + 路径 + 边界取值规则。
 * boundary_spec 保存取值规则而非数值，数值在计算时按 as_of 解析（保证迟到补录与历史复算语义）。
 */
public record Scenario(
        long id,
        String scenarioCode,
        long eventId,
        long networkVersionId,
        List<Long> pathSegmentIds,
        String boundarySpecJson,
        String conditionLabel,
        OffsetDateTime createdAt
) {}
