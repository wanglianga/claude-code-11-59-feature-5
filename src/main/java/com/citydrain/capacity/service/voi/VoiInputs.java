package com.citydrain.capacity.service.voi;

import com.citydrain.capacity.domain.ConditionPositionEntry;
import com.citydrain.capacity.domain.ReinspectionCandidate;

import java.util.List;

/**
 * VOI 估计的已解析输入（由编排层从仓储加载并解析 JSON 后构造），
 * 两条估计路线接收完全相同的输入，保证 evi 同口径、可对照。
 */
public final class VoiInputs {

    private VoiInputs() {}

    /** 候选 + 其位置状况 + 外部维修覆盖上下文，合并为单点估计输入。 */
    public record CandidateContext(
            ReinspectionCandidate candidate,
            ConditionPositionEntry entry,           // null = 评估版本未覆盖该位置
            boolean repairedAcceptedCovered,        // 已被维修覆盖且有完整验收
            boolean pendingRepair,                  // 存在未完成验收的维修（“维修后复检”场景）
            String coverageDetail,
            int currentRank,                        // 当前风险排序（1=最高）
            int totalPositions,
            String bottleneckSegmentCode
    ) {}

    /** 解析后的决策阈值（来自外部状况评估版本快照）。 */
    public record ThresholdContext(
            double monitorMin,
            double remediateMin,
            double remediationMargin,
            double remainingRiskLps
    ) {}

    /** 单候选估计输出（两路线同一结构，差异落在 breakdown/detail 字段）。 */
    public record CandidateScore(
            long candidateId,
            long segmentId,
            double evi,
            double decisionChangeProb,
            boolean lowValue,
            List<String> lowValueReasons,
            double resolutionFactor,
            boolean excludedRepaired,
            double expectedRiskReductionM,
            double[] riskLengthNarrowingM,
            NetworkNarrowing network,
            String outcomeBreakdownJson,
            String sensitivityDetailJson
    ) {}

    /** 网络影响结论可缩小范围。 */
    public record NetworkNarrowing(
            int currentRank,
            int totalPositions,
            String bottleneckSegmentCode,
            double bottleneckChangeProb,
            double intervalWidthReductionPct
    ) {}
}
