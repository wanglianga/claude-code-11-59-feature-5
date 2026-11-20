package com.citydrain.capacity.service.eval;

import com.citydrain.capacity.service.Hydraulics;

import java.util.List;
import java.util.Map;

/**
 * 单管段评估输出：可通过流量区间、相对设计需求余量、主导不确定参数、
 * 限制来源、不可判定原因、证据与覆盖账本行。
 */
public record SegmentEvaluation(
        long segmentId,
        Double qMinLps,
        Double qMaxLps,
        Double marginMin,
        Double marginMax,
        List<Hydraulics.Contribution> contributions,
        List<String> limitations,
        String undeterminableReason,
        Map<String, Object> evidence,
        List<LedgerLine> ledger
) {
    /** 覆盖账本行（未持久化形态）。 */
    public record LedgerLine(String item, String status, String detail) {}
}
