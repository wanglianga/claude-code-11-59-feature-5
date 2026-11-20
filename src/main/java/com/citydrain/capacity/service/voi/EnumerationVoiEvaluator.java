package com.citydrain.capacity.service.voi;

import com.citydrain.capacity.config.VoiProps;
import com.citydrain.capacity.domain.ConditionPositionEntry;
import com.citydrain.capacity.domain.ReinspectionCandidate;
import com.citydrain.capacity.domain.Vocab;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路线一：结果枚举法（ENUMERATION）。
 * 对每个候选位置枚举“发现更严重 / 确认现状 / 发现已修复”三种可能结果：
 * 以外部评估版本的业务阈值带（MONITOR/REMEDIATE 边界）划分先验风险分布的区域，
 * 按截断正态条件期望求各结果下的测得风险，经贝叶斯共轭得到后验决定并计算处置收益；
 * 结果概率取先验落入对应区域的边际概率（待验收维修时按贝叶斯调整修复概率），
 * 再按概率加权得到预期信息价值。
 *
 * 优点：直接反映离散阈值跳变与业务阈值，结果可逐项复核；
 * 代价：多点组合时结果空间随候选数组合增长，适合候选规模小、结果分布离散的场景。
 * 本路线独立产出 evi，不使用敏感度法做预处理或预热。
 */
@Component
public class EnumerationVoiEvaluator {

    private final VoiScoringSupport support;

    public EnumerationVoiEvaluator(VoiScoringSupport support) {
        this.support = support;
    }

    public String method() {
        return Vocab.VOI_ENUMERATION;
    }

    public VoiInputs.CandidateScore evaluate(VoiInputs.CandidateContext ctx,
                                             VoiInputs.ThresholdContext thresholds) {
        ReinspectionCandidate candidate = ctx.candidate();
        ConditionPositionEntry entry = ctx.entry();
        VoiProps props = support.props();

        // 评估版本未覆盖该位置：无法枚举结果，低价值
        if (entry == null) {
            return zero(candidate, List.of(Vocab.LV_OUTSIDE_ASSESSMENT), false,
                    Map.of("reason", "状况评估版本未覆盖该位置，无法枚举复检结果"));
        }
        // 已被维修覆盖且有完整验收：不重复推荐
        if (ctx.repairedAcceptedCovered()) {
            return zero(candidate, List.of(Vocab.LV_REPAIRED_ACCEPTED), true,
                    Map.of("reason", "已被维修作用范围覆盖且验收完整（ACCEPTED + 可追溯实测），不重复推荐",
                           "coverageDetail", ctx.coverageDetail() == null ? "" : ctx.coverageDetail()));
        }

        VoiMath.Thresholds th = new VoiMath.Thresholds(
                thresholds.monitorMin(), thresholds.remediateMin(),
                thresholds.remediationMargin(), thresholds.remainingRiskLps());
        double monitorMin = thresholds.monitorMin();
        double remediateMin = thresholds.remediateMin();

        double[] prior = VoiMath.prior(entry.riskScoreMin(), entry.riskScoreMax(),
                entry.evidenceConflict(), props);
        double priorMean = prior[0];
        double priorSigma = prior[1];
        double factor = support.measurementFactor(candidate, entry);
        double resolutionFactor = support.resolutionFactor(candidate, entry);
        double measSigma = VoiMath.measurementSigma(factor, entry.locationAmbiguityM(), props);
        double postSigma = VoiMath.posteriorSigma(priorSigma, measSigma);
        String currentDecision = entry.currentDecision();

        // 先验风险分布在三个决策带区域上的边际概率（离散结果概率的直接来源）
        double pLow = VoiMath.normalProbability(priorMean, priorSigma,
                Double.NEGATIVE_INFINITY, monitorMin);
        double pMid = VoiMath.normalProbability(priorMean, priorSigma, monitorMin, remediateMin);
        double pHigh = VoiMath.normalProbability(priorMean, priorSigma,
                remediateMin, Double.POSITIVE_INFINITY);

        // 结果 → 区域映射：发现更严重=更高一级带；确认现状=当前带；发现已修复=更低一级带
        int curLevel = level(currentDecision);
        double pWorse = curLevel < 2 ? bandProbability(curLevel + 1, pLow, pMid, pHigh) : 0.0;
        double pRepaired = curLevel > 0 ? bandProbability(curLevel - 1, pLow, pMid, pHigh) : 0.0;
        double pConfirm = bandProbability(curLevel, pLow, pMid, pHigh);

        if (ctx.pendingRepair()) {
            // 维修已实施但未完整验收：修复结果概率上调（至少 0.5），更严重结果下调后归一化
            pRepaired = Math.max(pRepaired, 0.5);
            pWorse *= 0.5;
            double s = pWorse + pConfirm + pRepaired;
            pWorse /= s;
            pConfirm /= s;
            pRepaired /= s;
        }

        // 各结果的测得风险取对应区域的截断正态条件期望
        double worseMean = curLevel < 2
                ? bandTruncatedMean(curLevel + 1, priorMean, priorSigma, monitorMin, remediateMin)
                : priorMean;
        double confirmMean = bandTruncatedMean(curLevel, priorMean, priorSigma, monitorMin, remediateMin);
        double repairedMeasured = curLevel > 0
                ? bandTruncatedMean(curLevel - 1, priorMean, priorSigma, monitorMin, remediateMin)
                : Math.min(props.getRepairedRiskScore(), priorMean);
        // “发现已修复”若条件期望仍偏高，则锚定到外部低风险基准
        double repairedMean = Math.min(repairedMeasured, props.getRepairedRiskScore());

        List<OutcomeSpec> specs = List.of(
                new OutcomeSpec(Vocab.OUT_WORSE, pWorse, worseMean, Math.min(curLevel + 1, 2)),
                new OutcomeSpec(Vocab.OUT_CONFIRM, pConfirm, confirmMean, curLevel),
                new OutcomeSpec(Vocab.OUT_REPAIRED, pRepaired, repairedMean, Math.max(curLevel - 1, 0)));

        List<Map<String, Object>> breakdown = new ArrayList<>();
        double decisionChangeProb = 0.0;
        double meanUtility = 0.0;
        double expectedRiskReduction = 0.0;
        double minNarrow = Double.MAX_VALUE;
        double maxNarrow = 0.0;
        boolean anyChange = false;

        for (OutcomeSpec spec : specs) {
            boolean negligible = spec.probability() < 1e-9;
            double postMean;
            String postDecision;
            double measuredShown;
            if (negligible) {
                // 目标决策带概率≈0：截断期望无定义，按目标带代表值展示且不参与任何变化统计
                postMean = bandRepresentative(spec.targetLevel(), monitorMin, remediateMin);
                postDecision = decisionOfLevel(spec.targetLevel());
                measuredShown = postMean;
            } else {
                postMean = VoiMath.posteriorMean(priorMean, priorSigma, spec.measuredMean(), measSigma);
                postDecision = th.decisionOf(postMean);
                measuredShown = spec.measuredMean();
            }
            double utility = support.decisionUtility(currentDecision, postDecision);
            double postRiskLength = support.posteriorRiskLengthM(
                    entry.currentRiskLengthM(), priorMean, postMean);
            boolean changed = !postDecision.equals(currentDecision);
            anyChange |= changed && !negligible;
            decisionChangeProb += spec.probability() * (changed ? 1.0 : 0.0);
            meanUtility += spec.probability() * utility;
            double reduction = Math.max(0.0, entry.currentRiskLengthM() - postRiskLength);
            expectedRiskReduction += spec.probability() * reduction;
            double narrowing = Math.abs(entry.currentRiskLengthM() - postRiskLength);
            minNarrow = Math.min(minNarrow, narrowing);
            maxNarrow = Math.max(maxNarrow, narrowing);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("outcome", spec.outcome());
            row.put("probability", round4(spec.probability()));
            row.put("measuredRiskMean", round2(measuredShown));
            row.put("posteriorRiskMean", round2(postMean));
            row.put("posteriorRiskSigma", round2(postSigma));
            row.put("posteriorDecision", postDecision);
            row.put("decisionChanged", changed && !negligible);
            row.put("utility", round4(utility));
            row.put("posteriorRiskLengthM", round2(postRiskLength));
            breakdown.add(row);
        }
        minNarrow = minNarrow == Double.MAX_VALUE ? 0.0 : minNarrow;

        // 网络影响：瓶颈/近瓶颈位置的决定变化更可能改变网络结论
        double bottleneckChangeProb = networkBottleneckProb(ctx, decisionChangeProb);
        double widthReductionPct = (1.0 - postSigma / priorSigma) * 100.0;
        double evi = support.composeEvi(decisionChangeProb, meanUtility, expectedRiskReduction,
                entry.currentRiskLengthM(), bottleneckChangeProb, widthReductionPct, resolutionFactor);

        List<String> lowReasons = support.lowValueReasons(!anyChange, decisionChangeProb, resolutionFactor);
        boolean lowValue = !lowReasons.isEmpty();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("priorRiskMean", round2(priorMean));
        detail.put("priorRiskSigma", round2(priorSigma));
        detail.put("measurementSigma", round2(measSigma));
        detail.put("posteriorRiskSigma", round2(postSigma));
        detail.put("currentDecision", currentDecision);
        detail.put("thresholds", thresholds);
        detail.put("decisionBandProbabilities", Map.of(
                "NO_ACTION", round4(pLow), "MONITOR", round4(pMid), "REMEDIATE", round4(pHigh)));
        detail.put("outcomes", breakdown);
        detail.put("measurementFactor", round4(factor));
        detail.put("pendingRepair", ctx.pendingRepair());

        return new VoiInputs.CandidateScore(candidate.id(), candidate.segmentId(), evi,
                round4(decisionChangeProb), lowValue, lowReasons, round4(resolutionFactor),
                false, round2(expectedRiskReduction),
                new double[]{round2(minNarrow), round2(maxNarrow)},
                new VoiInputs.NetworkNarrowing(ctx.currentRank(), ctx.totalPositions(),
                        ctx.bottleneckSegmentCode(), round4(bottleneckChangeProb),
                        round2(widthReductionPct)),
                support.toJson(detail), null);
    }

    private int level(String decision) {
        return switch (decision) {
            case Vocab.DEC_NO_ACTION -> 0;
            case Vocab.DEC_MONITOR -> 1;
            case Vocab.DEC_REMEDIATE -> 2;
            default -> 1;
        };
    }

    private double bandProbability(int level, double pLow, double pMid, double pHigh) {
        return switch (level) {
            case 0 -> pLow;
            case 1 -> pMid;
            default -> pHigh;
        };
    }

    private double bandTruncatedMean(int level, double mean, double sigma,
                                     double monitorMin, double remediateMin) {
        return switch (level) {
            case 0 -> VoiMath.truncatedMean(mean, sigma, Double.NEGATIVE_INFINITY, monitorMin);
            case 1 -> VoiMath.truncatedMean(mean, sigma, monitorMin, remediateMin);
            default -> VoiMath.truncatedMean(mean, sigma, remediateMin, Double.POSITIVE_INFINITY);
        };
    }

    /** 零概率结果的展示用代表值：取目标决策带中点。 */
    private double bandRepresentative(int level, double monitorMin, double remediateMin) {
        return switch (level) {
            case 0 -> monitorMin / 2.0;
            case 1 -> (monitorMin + remediateMin) / 2.0;
            default -> (remediateMin + 100.0) / 2.0;
        };
    }

    private String decisionOfLevel(int level) {
        return switch (level) {
            case 0 -> Vocab.DEC_NO_ACTION;
            case 1 -> Vocab.DEC_MONITOR;
            default -> Vocab.DEC_REMEDIATE;
        };
    }

    private double networkBottleneckProb(VoiInputs.CandidateContext ctx, double decisionChangeProb) {
        if (ctx.currentRank() <= 2) {
            return decisionChangeProb;
        }
        return decisionChangeProb * 0.3;
    }

    private VoiInputs.CandidateScore zero(ReinspectionCandidate candidate, List<String> reasons,
                                          boolean excludedRepaired, Map<String, Object> detail) {
        return new VoiInputs.CandidateScore(candidate.id(), candidate.segmentId(), 0.0, 0.0,
                true, reasons, 1.0, excludedRepaired, 0.0, new double[]{0.0, 0.0},
                new VoiInputs.NetworkNarrowing(0, 0, null, 0.0, 0.0),
                support.toJson(detail), null);
    }

    private record OutcomeSpec(String outcome, double probability, double measuredMean, int targetLevel) {}

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
