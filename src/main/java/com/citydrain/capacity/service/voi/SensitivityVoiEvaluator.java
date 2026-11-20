package com.citydrain.capacity.service.voi;

import com.citydrain.capacity.config.VoiProps;
import com.citydrain.capacity.domain.ConditionPositionEntry;
import com.citydrain.capacity.domain.ReinspectionCandidate;
import com.citydrain.capacity.domain.Vocab;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路线二：局部敏感度法（SENSITIVITY）。
 * 在观测不确定度上对风险/影响模型做局部线性化：先验 N(μ,σ) 经复检测量 N(θ,σ_meas)
 * 收缩为后验，后验均值作为随机变量的边际分布为 N(μ, σ⁴/(σ²+σ_meas²))；
 * 仅在当前决定带边界 ±localWindowStd·σ 的局部窗口内计算阈值穿越概率，
 * 以同一套业务阈值与同一 evi 合成口径产出近似信息价值。
 *
 * 优点：每候选仅常数次 CDF 计算，适合大批候选筛选；
 * 代价：局部窗口外的离散阈值跳变可能被忽略（detail 中显式标注 mayMissDiscreteJumps），
 * 且不枚举“发现已修复”等离散结果——维修后复检等强离散场景应选用枚举法。
 * 本路线独立产出完整 evi，不作为枚举法的预处理或预热。
 */
@Component
public class SensitivityVoiEvaluator {

    private final VoiScoringSupport support;

    public SensitivityVoiEvaluator(VoiScoringSupport support) {
        this.support = support;
    }

    public String method() {
        return Vocab.VOI_SENSITIVITY;
    }

    public VoiInputs.CandidateScore evaluate(VoiInputs.CandidateContext ctx,
                                             VoiInputs.ThresholdContext thresholds) {
        ReinspectionCandidate candidate = ctx.candidate();
        ConditionPositionEntry entry = ctx.entry();
        VoiProps props = support.props();

        if (entry == null) {
            return zero(candidate, List.of(Vocab.LV_OUTSIDE_ASSESSMENT), false,
                    Map.of("reason", "状况评估版本未覆盖该位置，敏感度无定义"));
        }
        if (ctx.repairedAcceptedCovered()) {
            return zero(candidate, List.of(Vocab.LV_REPAIRED_ACCEPTED), true,
                    Map.of("reason", "已被维修作用范围覆盖且验收完整（ACCEPTED + 可追溯实测），不重复推荐",
                           "coverageDetail", ctx.coverageDetail()));
        }

        double[] prior = VoiMath.prior(entry.riskScoreMin(), entry.riskScoreMax(),
                entry.evidenceConflict(), props);
        double priorMean = prior[0];
        double priorSigma = prior[1];
        double factor = support.measurementFactor(candidate, entry);
        double resolutionFactor = support.resolutionFactor(candidate, entry);
        double measSigma = VoiMath.measurementSigma(factor, entry.locationAmbiguityM(), props);
        double postSigma = VoiMath.posteriorSigma(priorSigma, measSigma);
        // 后验均值的边际标准差：复检后决定带边界的剩余不确定度
        double postMeanSigma = priorSigma * priorSigma
                / Math.sqrt(priorSigma * priorSigma + measSigma * measSigma);

        // 局部窗口内的阈值边界穿越概率（离散阈值跳变的局部近似）
        double window = props.getSensitivityLocalWindowStd() * postMeanSigma;
        List<Map<String, Object>> boundaryDetails = new java.util.ArrayList<>();
        double decisionChangeProb = 0.0;
        boolean mayMissDiscreteJumps = false;
        for (double boundary : List.of(thresholds.monitorMin(), thresholds.remediateMin())) {
            double distance = Math.abs(priorMean - boundary);
            double crossProb = 0.0;
            boolean inWindow = distance <= window;
            if (inWindow) {
                // 后验均值落到边界另一侧的概率
                crossProb = priorMean >= boundary
                        ? VoiMath.normalCdf(boundary, priorMean, postMeanSigma)
                        : 1.0 - VoiMath.normalCdf(boundary, priorMean, postMeanSigma);
                decisionChangeProb += crossProb;
            } else if (distance <= window * 2.0) {
                // 局部窗口外但两倍窗口内：存在被忽略的阈值跳变风险
                mayMissDiscreteJumps = true;
            }
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("boundaryRiskScore", boundary);
            b.put("distanceFromPriorMean", round2(distance));
            b.put("localWindowHalfWidth", round2(window));
            b.put("inLocalWindow", inWindow);
            b.put("crossingProbability", round4(crossProb));
            boundaryDetails.add(b);
        }
        decisionChangeProb = VoiMath.clamp(decisionChangeProb, 0.0, 1.0);

        // 局部线性化：风险长度随后验不确定度收窄而缩减，E[max(0,z)] = 1/√(2π)
        double widthReductionFraction = VoiMath.clamp(1.0 - postSigma / priorSigma, 0.0, 1.0);
        double expectedRiskReduction = entry.currentRiskLengthM() > 0
                ? entry.currentRiskLengthM() * widthReductionFraction / Math.sqrt(2.0 * Math.PI) : 0.0;
        double[] riskLengthNarrowing = new double[]{
                0.0, round2(entry.currentRiskLengthM() * widthReductionFraction)};

        double bottleneckChangeProb = ctx.currentRank() <= 2 ? decisionChangeProb : decisionChangeProb * 0.3;
        double widthReductionPct = widthReductionFraction * 100.0;
        // 近似路线下决策收益取期望带级变化的保守值 0.55
        double meanUtilityApprox = decisionChangeProb > 0 ? 0.55 : 0.0;
        double evi = support.composeEvi(decisionChangeProb, meanUtilityApprox, expectedRiskReduction,
                entry.currentRiskLengthM(), bottleneckChangeProb, widthReductionPct, resolutionFactor);

        List<String> lowReasons = support.lowValueReasons(decisionChangeProb <= 0.0,
                decisionChangeProb, resolutionFactor);
        boolean lowValue = !lowReasons.isEmpty();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("priorRiskMean", round2(priorMean));
        detail.put("priorRiskSigma", round2(priorSigma));
        detail.put("measurementSigma", round2(measSigma));
        detail.put("posteriorRiskSigma", round2(postSigma));
        detail.put("posteriorMeanSigma", round2(postMeanSigma));
        detail.put("currentDecision", entry.currentDecision());
        detail.put("thresholds", thresholds);
        detail.put("boundaryCrossings", boundaryDetails);
        detail.put("measurementFactor", round4(factor));
        detail.put("mayMissDiscreteJumps", mayMissDiscreteJumps);
        detail.put("approximationNote", "局部敏感度近似：不枚举离散复检结果，维修后复检等强离散场景建议使用 ENUMERATION");
        if (ctx.pendingRepair()) {
            detail.put("pendingRepair", true);
            detail.put("pendingRepairNote", "存在待验收维修：离散修复结果无法由敏感度法表达，枚举法价值估计更可靠");
        }

        return new VoiInputs.CandidateScore(candidate.id(), candidate.segmentId(), evi,
                round4(decisionChangeProb), lowValue, lowReasons, round4(resolutionFactor),
                false, round2(expectedRiskReduction), riskLengthNarrowing,
                new VoiInputs.NetworkNarrowing(ctx.currentRank(), ctx.totalPositions(),
                        ctx.bottleneckSegmentCode(), round4(bottleneckChangeProb),
                        round2(widthReductionPct)),
                null, support.toJson(detail));
    }

    private VoiInputs.CandidateScore zero(ReinspectionCandidate candidate, List<String> reasons,
                                          boolean excludedRepaired, Map<String, Object> detail) {
        return new VoiInputs.CandidateScore(candidate.id(), candidate.segmentId(), 0.0, 0.0,
                true, reasons, 1.0, excludedRepaired, 0.0, new double[]{0.0, 0.0},
                new VoiInputs.NetworkNarrowing(0, 0, null, 0.0, 0.0),
                null, support.toJson(detail));
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
