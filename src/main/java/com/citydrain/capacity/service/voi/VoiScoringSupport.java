package com.citydrain.capacity.service.voi;

import com.citydrain.capacity.config.VoiProps;
import com.citydrain.capacity.domain.ConditionPositionEntry;
import com.citydrain.capacity.domain.ReinspectionCandidate;
import com.citydrain.capacity.domain.Vocab;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 两条 VOI 路线共用的评分支持：方法能力系数、分辨率降权、低价值判定与 evi 合成。
 * 路线差异只体现在决策变化概率的求法（离散枚举 vs 局部敏感度近似），
 * 合成口径完全一致，保证同一复检在两路线下的 evi 可对照。
 */
@Component
public class VoiScoringSupport {

    private final VoiProps props;
    private final ObjectMapper objectMapper;

    public VoiScoringSupport(VoiProps props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public VoiProps props() {
        return props;
    }

    /**
     * 方法能力系数 measurementFactor ∈ (0,1]：
     * 分辨率不足以区分相邻缺陷时降权；定位误差越大降权越多。
     * 相邻缺陷间距从位置条目 note 的 adjacentDefectSpacingM（米）读取，缺省取候选覆盖长度。
     */
    public double measurementFactor(ReinspectionCandidate candidate, ConditionPositionEntry entry) {
        double resolutionM = candidate.methodResolutionMm() / 1000.0;
        double spacingM = adjacentSpacingM(candidate, entry);
        // 分辨率达到间距的一半即可区分；越不足系数越低，下限 0.25
        double resolutionFactor = VoiMath.clamp(spacingM / Math.max(resolutionM * 2.0, 1e-6), 0.25, 1.0);
        double locationFactor = VoiMath.clamp(
                props.getReferenceLocationErrorM() / Math.max(candidate.locationErrorM(), 1e-6), 0.3, 1.0);
        return VoiMath.clamp(resolutionFactor * locationFactor, 0.05, 1.0);
    }

    /** 仅分辨率部分的降权系数（单独落库，便于解释“分辨率不足”低价值原因）。 */
    public double resolutionFactor(ReinspectionCandidate candidate, ConditionPositionEntry entry) {
        double resolutionM = candidate.methodResolutionMm() / 1000.0;
        double spacingM = adjacentSpacingM(candidate, entry);
        return VoiMath.clamp(spacingM / Math.max(resolutionM * 2.0, 1e-6), 0.25, 1.0);
    }

    private double adjacentSpacingM(ReinspectionCandidate candidate, ConditionPositionEntry entry) {
        double coverage = rangeLengthM(candidate.locationRangeJson());
        if (entry != null && entry.note() != null && !entry.note().isBlank()) {
            try {
                JsonNode note = objectMapper.readTree(entry.note());
                if (note.path("adjacentDefectSpacingM").isNumber()) {
                    return note.path("adjacentDefectSpacingM").asDouble();
                }
            } catch (Exception ignored) {
                // note 非 JSON 时退回覆盖长度
            }
        }
        return Math.max(coverage, 1.0);
    }

    public double rangeLengthM(String locationRangeJson) {
        try {
            JsonNode r = objectMapper.readTree(locationRangeJson);
            double start = r.path("startM").asDouble(0);
            double end = r.path("endM").asDouble(0);
            return Math.max(0.0, end - start);
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * 处置决定变化的业务收益（[0,1]）：直接反映外部评估版本的业务阈值带，
     * 跨级变化（REMEDIATE↔NO_ACTION）收益最高，同级为 0。
     */
    public double decisionUtility(String currentDecision, String posteriorDecision) {
        if (currentDecision.equals(posteriorDecision)) {
            return 0.0;
        }
        int cur = level(currentDecision);
        int post = level(posteriorDecision);
        return switch (Math.abs(cur - post)) {
            case 2 -> 1.0;
            case 1 -> 0.55;
            default -> 0.0;
        };
    }

    private int level(String decision) {
        return switch (decision) {
            case Vocab.DEC_NO_ACTION -> 0;
            case Vocab.DEC_MONITOR -> 1;
            case Vocab.DEC_REMEDIATE -> 2;
            default -> 1;
        };
    }

    /** 后验风险长度：随后验风险分线性缩放（截断 0.2~2.0 倍），确定性可复核。 */
    public double posteriorRiskLengthM(double currentRiskLengthM, double priorMean, double posteriorMean) {
        double ratio = VoiMath.clamp(posteriorMean / Math.max(priorMean, 1e-6), 0.2, 2.0);
        return Math.round(currentRiskLengthM * ratio * 10.0) / 10.0;
    }

    /**
     * 统一 evi 合成：决策变化 / 剩余风险长度 / 网络影响结论三分量，按配置权重合成，
     * 再乘以分辨率降权系数（方法无法区分相邻缺陷时价值下降）。
     */
    public double composeEvi(double decisionChangeProb, double meanUtility,
                             double expectedRiskReductionM, double currentRiskLengthM,
                             double bottleneckChangeProb, double intervalWidthReductionPct,
                             double resolutionFactor) {
        double decisionComponent = VoiMath.clamp(decisionChangeProb * meanUtility, 0, 1);
        double riskLengthComponent = currentRiskLengthM > 0
                ? VoiMath.clamp(expectedRiskReductionM / currentRiskLengthM, 0, 1) : 0.0;
        double networkComponent = VoiMath.clamp(
                bottleneckChangeProb * intervalWidthReductionPct / 100.0, 0, 1);
        VoiProps.Weight w = props.getWeight();
        double raw = w.getDecision() * decisionComponent
                + w.getRiskLength() * riskLengthComponent
                + w.getNetwork() * networkComponent;
        return Math.round(VoiMath.clamp(raw * resolutionFactor, 0, 1) * 1000.0) / 1000.0;
    }

    /** 低价值判定：所有结果都无法改变决定，或决策变化概率低于阈值，或分辨率严重不足。 */
    public List<String> lowValueReasons(boolean immutable, double decisionChangeProb,
                                        double resolutionFactor) {
        List<String> reasons = new ArrayList<>();
        if (immutable) {
            reasons.add(Vocab.LV_DECISION_IMMUTABLE);
        }
        if (decisionChangeProb < props.getLowValueDecisionProb()) {
            reasons.add(Vocab.LV_DECISION_IMMUTABLE);
        }
        if (resolutionFactor < 0.5) {
            reasons.add(Vocab.LV_RESOLUTION_INSUFFICIENT);
        }
        return reasons.stream().distinct().toList();
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("VOI 结果序列化失败", e);
        }
    }
}
