package com.citydrain.capacity.service.eval;

import com.citydrain.capacity.config.HydraulicsProps;
import com.citydrain.capacity.domain.ExternalRef;
import com.citydrain.capacity.domain.Observation;
import com.citydrain.capacity.domain.ParamVersion;
import com.citydrain.capacity.domain.Segment;
import com.citydrain.capacity.domain.Vocab;
import com.citydrain.capacity.repo.ExternalRefRepo;
import com.citydrain.capacity.repo.ObservationRepo;
import com.citydrain.capacity.repo.ParamVersionRepo;
import com.citydrain.capacity.service.Hydraulics;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 路线B：经已知工况校准的水力情景集合，多段联合计算流量与水位可行范围。
 * - 校准：仅采用流量与上/下游水位在时间对齐容差内的同刻工况（未对齐的峰值不合成）；
 *   几何参数取采样时刻有效的参数版本（不以事后版本回填历史工况）。
 * - 联合：从最下游边界水位区间出发，逐段向上游传播壅水（回水及边界联动）。
 * 与路线A相互独立，结果可对照但不互相预处理/核验。
 */
@Component
public class CalibratedEnsembleEvaluator {

    private final ParamVersionRepo paramRepo;
    private final ObservationRepo obsRepo;
    private final ExternalRefRepo extRepo;
    private final SegmentIntervalMath math;
    private final HydraulicsProps props;

    public CalibratedEnsembleEvaluator(ParamVersionRepo paramRepo, ObservationRepo obsRepo,
                                       ExternalRefRepo extRepo, SegmentIntervalMath math,
                                       HydraulicsProps props) {
        this.paramRepo = paramRepo;
        this.obsRepo = obsRepo;
        this.extRepo = extRepo;
        this.math = math;
        this.props = props;
    }

    /**
     * @param path 上游→下游有序管段
     * @return 与 path 同序的评估结果
     */
    public List<SegmentEvaluation> evaluatePath(List<Segment> path, EvalContext ctx) {
        // 下游边界水位区间（事件窗口内、as_of 前入库）
        List<Observation> bnd = obsRepo.forComputation(Vocab.OBS_DOWNSTREAM_LEVEL,
                ctx.downstreamBoundaryCode(), ctx.windowStart(), ctx.windowEnd(), ctx.asOf());
        double[] tw = null;
        List<Long> twIds = List.of();
        if (!bnd.isEmpty()) {
            tw = new double[]{
                    bnd.stream().mapToDouble(Observation::valueNum).min().orElseThrow(),
                    bnd.stream().mapToDouble(Observation::valueNum).max().orElseThrow()};
            twIds = bnd.stream().map(Observation::id).toList();
        }

        Map<Long, SegmentEvaluation> byId = new LinkedHashMap<>();
        // 下游→上游联合传播
        for (int i = path.size() - 1; i >= 0; i--) {
            Segment seg = path.get(i);
            ParamVersion pv = paramRepo.resolveAt(seg.id(), ctx.asOf()).orElse(null);
            Calibration cal = calibrate(seg, ctx);

            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("calibration", cal.evidence());
            double[] headwater = null;
            if (tw != null && pv != null && pv.slopeMin() != null && pv.slopeMax() != null) {
                headwater = new double[]{
                        tw[0] + pv.slopeMin() * seg.lengthM(),
                        tw[1] + pv.slopeMax() * seg.lengthM()};
                extra.put("tailwaterRangeM", List.of(tw[0], tw[1]));
                extra.put("headwaterRangeM", List.of(headwater[0], headwater[1]));
            }

            List<ExternalRef> defects = extRepo.listBySegmentAndType(seg.id(), Vocab.REF_DEFECT);
            List<ExternalRef> inspections = extRepo.listBySegmentAndType(seg.id(), Vocab.REF_INSPECTION);

            SegmentEvaluation eval = math.evaluate(seg, pv, tw, twIds, cal.intervalOrNull(),
                    ctx.gateFor(seg.segmentCode()), defects, inspections, extra);
            if (cal.insufficient() && !eval.limitations().contains(Vocab.LIM_CALIBRATION_INSUFFICIENT)) {
                eval.limitations().add(Vocab.LIM_CALIBRATION_INSUFFICIENT);
            }
            eval.ledger().addAll(cal.ledgerLines());
            byId.put(seg.id(), eval);

            // 本段上游水位区间成为下一段（更上游）的尾水：回水联动
            if (headwater != null) {
                tw = headwater;
                twIds = List.of(); // 传播值，非直接观测
            } else {
                tw = null;
                twIds = List.of();
            }
        }
        return path.stream().map(s -> byId.get(s.id())).toList();
    }

    /** 校准结果：糙率区间（可能为 null → 退回声明/缺省区间）与账本行。 */
    private record Calibration(double[] intervalOrNull, boolean insufficient,
                               Map<String, Object> evidence,
                               List<SegmentEvaluation.LedgerLine> ledgerLines) {}

    private Calibration calibrate(Segment seg, EvalContext ctx) {
        List<Observation> flows = obsRepo.forCalibration(Vocab.OBS_FLOW, seg.segmentCode(), ctx.asOf());
        List<Double> samples = new ArrayList<>();
        List<Long> flowIds = new ArrayList<>();
        List<Long> levelIds = new ArrayList<>();
        int rejected = 0;
        long tol = props.alignmentToleranceSeconds();

        for (Observation f : flows) {
            // 时间对齐：同一时刻（容差内）的流量 + 上游水位 + 下游水位才构成一个校准工况
            Optional<Observation> us = obsRepo.nearest(Vocab.OBS_WATER_LEVEL, seg.fromNode(),
                    f.sampledAt(), tol, ctx.asOf());
            Optional<Observation> ds = obsRepo.nearest(Vocab.OBS_DOWNSTREAM_LEVEL, seg.toNode(),
                    f.sampledAt(), tol, ctx.asOf())
                    .or(() -> obsRepo.nearest(Vocab.OBS_WATER_LEVEL, seg.toNode(),
                            f.sampledAt(), tol, ctx.asOf()));
            if (us.isEmpty() || ds.isEmpty()) {
                rejected++;
                continue;
            }
            // 几何取采样时刻有效的参数版本（不以事后版本回填历史工况）
            Optional<ParamVersion> pvAt = paramRepo.resolveValidAtCreatedBefore(
                    seg.id(), f.sampledAt(), ctx.asOf());
            if (pvAt.isEmpty() || pvAt.get().diameterMinMm() == null
                    || pvAt.get().diameterMaxMm() == null) {
                rejected++;
                continue;
            }
            double sEff = (us.get().valueNum() - ds.get().valueNum()) / seg.lengthM();
            double qCms = f.valueNum() / 1000.0;
            if (sEff <= 0 || qCms <= 0) {
                rejected++;
                continue;
            }
            ParamVersion pv = pvAt.get();
            double dMidM = (pv.diameterMinMm() + pv.diameterMaxMm()) / 2000.0;
            double siltMid = (pv.siltRatioMin() != null && pv.siltRatioMax() != null)
                    ? (pv.siltRatioMin() + pv.siltRatioMax()) / 2
                    : props.defaultSiltMax() / 2;
            double dEff = Hydraulics.effectiveDiameterM(dMidM, siltMid);
            double n = Hydraulics.C * Math.pow(dEff, 8.0 / 3.0) * Math.sqrt(sEff) / qCms;
            if (n < 0.005 || n > 0.1) { // 物理不可信样本
                rejected++;
                continue;
            }
            samples.add(n);
            flowIds.add(f.id());
            levelIds.add(us.get().id());
            levelIds.add(ds.get().id());
        }

        List<SegmentEvaluation.LedgerLine> ledger = new ArrayList<>();
        ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_OBS_FLOW,
                flows.isEmpty() ? Vocab.ST_MISSING : Vocab.ST_AVAILABLE,
                "流量观测 " + flows.size() + " 条（as_of 前入库）"));

        double[] interval = null;
        boolean insufficient;
        String detail;
        if (samples.size() >= 3) {
            double min = samples.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            double max = samples.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            interval = new double[]{min, max};
            insufficient = false;
            detail = "校准样本 " + samples.size() + " 条，糙率区间 [" + min + ", " + max + "]";
        } else if (!samples.isEmpty()) {
            double min = samples.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            double max = samples.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            interval = new double[]{min * 0.95, max * 1.05};
            insufficient = true;
            detail = "校准样本不足（" + samples.size() + " 条 < 3），糙率区间外扩 5%";
        } else {
            insufficient = true;
            detail = "无有效校准样本，退回声明/缺省糙率区间";
        }
        if (rejected > 0) {
            detail += "；拒绝 " + rejected + " 条（时间未对齐/参数版本不覆盖/水力条件无效）";
        }
        ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_CALIBRATION_SAMPLES,
                insufficient ? Vocab.ST_ASSUMED : Vocab.ST_AVAILABLE, detail));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sampleCount", samples.size());
        evidence.put("rejectedCount", rejected);
        evidence.put("samples", samples);
        evidence.put("flowObservationIds", flowIds);
        evidence.put("levelObservationIds", levelIds);
        evidence.put("insufficient", insufficient);
        return new Calibration(interval, insufficient, evidence, ledger);
    }
}
