package com.citydrain.capacity.service.eval;

import com.citydrain.capacity.domain.GateState;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一次计算运行的解析上下文：事件窗口、知识截止 as_of、闸门钉定、下游边界点。
 *
 * @param gatePins 按管段号的闸门钉定（条件情景）；冲突状态已拆分为不同情景，不在此平均
 */
public record EvalContext(
        OffsetDateTime windowStart,
        OffsetDateTime windowEnd,
        OffsetDateTime asOf,
        Map<String, GatePin> gatePins,
        String downstreamBoundaryCode
) {
    /** 闸门钉定：状态 + 支持观测；supportedAtAsOf=false 表示 as_of 前无支持观测入库。 */
    public record GatePin(String gateCode, GateState state, List<Long> observationIds,
                          boolean supportedAtAsOf) {}

    public GatePin gateFor(String segmentCode) {
        return gatePins == null ? null : gatePins.get(segmentCode);
    }
}
