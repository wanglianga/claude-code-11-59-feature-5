package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/** 管段：名义属性与设计需求。经确认的测量区间见 {@link ParamVersion}。 */
public record Segment(
        long id,
        long networkVersionId,
        String segmentCode,
        String fromNode,
        String toNode,
        double lengthM,
        String shape,
        double nominalDiameterMm,
        double upstreamInvertM,
        double downstreamInvertM,
        double designFlowLps,
        OffsetDateTime createdAt
) {}
