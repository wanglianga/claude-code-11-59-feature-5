package com.citydrain.capacity.service.eval;

import com.citydrain.capacity.config.HydraulicsProps;
import com.citydrain.capacity.domain.ExternalRef;
import com.citydrain.capacity.domain.GateState;
import com.citydrain.capacity.domain.ParamVersion;
import com.citydrain.capacity.domain.Segment;
import com.citydrain.capacity.domain.Vocab;
import com.citydrain.capacity.service.Hydraulics;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单管段区间计算：把已解析输入（参数版本区间、尾水区间、糙率、闸门因子）
 * 通过水力核心传播为可通过流量区间。两条评估路线共用本组件解析单段，
 * 但各自独立解析输入（路线A用声明区间+逐段尾水；路线B用校准糙率+联合传播尾水），
 * 互不作为对方的预处理或核验。
 */
@Component
public class SegmentIntervalMath {

    private final HydraulicsProps props;

    public SegmentIntervalMath(HydraulicsProps props) {
        this.props = props;
    }

    /**
     * @param tailwaterM        尾水区间 [min,max]（绝对水位 m）；null = 无观测，按假设扩大
     * @param roughnessOverride 校准糙率区间（路线B）；null = 使用参数版本声明区间
     */
    public SegmentEvaluation evaluate(
            Segment seg,
            ParamVersion pv,
            double[] tailwaterM,
            List<Long> tailwaterObsIds,
            double[] roughnessOverride,
            EvalContext.GatePin gate,
            List<ExternalRef> defectRefs,
            List<ExternalRef> inspectionRefs,
            Map<String, Object> extraEvidence) {

        List<SegmentEvaluation.LedgerLine> ledger = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();

        // 外部只读引用：仅作证据上下文，不参与计算、不裁决
        evidence.put("externalDefectRefIds", defectRefs.stream().map(ExternalRef::id).toList());
        evidence.put("externalInspectionRefIds", inspectionRefs.stream().map(ExternalRef::id).toList());
        ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_EXT_DEFECT_REF,
                defectRefs.isEmpty() ? Vocab.ST_MISSING : Vocab.ST_AVAILABLE,
                defectRefs.isEmpty() ? "无缺陷观测只读引用" : "只读引用 " + defectRefs.size() + " 条（不参与计算）"));
        ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_EXT_INSPECTION_REF,
                inspectionRefs.isEmpty() ? Vocab.ST_MISSING : Vocab.ST_AVAILABLE,
                inspectionRefs.isEmpty() ? "无检查覆盖只读引用" : "只读引用 " + inspectionRefs.size() + " 条（不参与计算）"));

        // 1) 参数版本：as_of 无有效版本 → 不可判定，不得以最新值回填
        if (pv == null) {
            ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_PARAM_VERSION, Vocab.ST_MISSING,
                    "as_of 时点无有效参数版本（不以最新值回填历史）"));
            evidence.put("observationIds", Map.of("downstreamLevel", tailwaterObsIds));
            return undeterminable(seg, Vocab.REASON_NO_PARAM_VERSION, limitations, ledger, evidence);
        }
        ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_PARAM_VERSION, Vocab.ST_AVAILABLE,
                "参数版本 v" + pv.versionNo() + "（来源 " + pv.source() + "）"));
        evidence.put("parameterVersionId", pv.id());
        evidence.put("parameterVersionNo", pv.versionNo());
        evidence.put("parameterSource", pv.source());

        // 2) 几何与坡度：缺失 → 不可判定
        if (pv.diameterMinMm() == null || pv.diameterMaxMm() == null) {
            ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_PARAM_GEOMETRY, Vocab.ST_MISSING,
                    "有效断面直径区间缺失"));
            return undeterminable(seg, Vocab.REASON_GEOMETRY_MISSING, limitations, ledger, evidence);
        }
        ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_PARAM_GEOMETRY, Vocab.ST_AVAILABLE,
                "有效直径区间 [" + pv.diameterMinMm() + ", " + pv.diameterMaxMm() + "] mm"));
        if (pv.slopeMin() == null || pv.slopeMax() == null) {
            ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_PARAM_SLOPE, Vocab.ST_MISSING,
                    "坡度区间缺失"));
            return undeterminable(seg, Vocab.REASON_SLOPE_MISSING, limitations, ledger, evidence);
        }
        ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_PARAM_SLOPE, Vocab.ST_AVAILABLE,
                "坡度区间 [" + pv.slopeMin() + ", " + pv.slopeMax() + "]"));

        // 3) 糙率：校准覆盖 > 声明区间 > 缺省扩大
        double nMin, nMax;
        if (roughnessOverride != null) {
            nMin = roughnessOverride[0];
            nMax = roughnessOverride[1];
            ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_PARAM_ROUGHNESS, Vocab.ST_AVAILABLE,
                    "校准糙率区间 [" + nMin + ", " + nMax + "]"));
        } else if (pv.roughnessMin() != null && pv.roughnessMax() != null) {
            nMin = pv.roughnessMin();
            nMax = pv.roughnessMax();
            ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_PARAM_ROUGHNESS, Vocab.ST_AVAILABLE,
                    "声明糙率区间 [" + nMin + ", " + nMax + "]"));
        } else {
            nMin = props.defaultRoughnessMin();
            nMax = props.defaultRoughnessMax();
            limitations.add(Vocab.LIM_ROUGHNESS_MISSING);
            assumptions.add("糙率缺失，采用缺省区间 [" + nMin + ", " + nMax + "]");
            ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_PARAM_ROUGHNESS, Vocab.ST_ASSUMED,
                    "糙率缺失，扩大为缺省区间 [" + nMin + ", " + nMax + "]"));
        }

        // 4) 淤积：未知 → 扩大区间并标记
        double siltMin, siltMax;
        if (pv.siltRatioMin() != null && pv.siltRatioMax() != null) {
            siltMin = pv.siltRatioMin();
            siltMax = pv.siltRatioMax();
            ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_PARAM_SILTATION, Vocab.ST_AVAILABLE,
                    "淤积率区间 [" + siltMin + ", " + siltMax + "]"));
        } else {
            siltMin = 0.0;
            siltMax = props.defaultSiltMax();
            limitations.add(Vocab.LIM_SILTATION_MISSING);
            assumptions.add("淤积率未知，假设区间 [0, " + siltMax + "]");
            ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_PARAM_SILTATION, Vocab.ST_ASSUMED,
                    "淤积率未知，假设区间 [0, " + siltMax + "]"));
        }

        // 5) 尾水：无观测 → 假设区间扩大并标记
        double physicalDiameterM = pv.diameterMaxMm() / 1000.0;
        double crownDs = seg.downstreamInvertM() + physicalDiameterM;
        double twMin, twMax;
        Map<String, Object> obsIds = new LinkedHashMap<>();
        obsIds.put("downstreamLevel", tailwaterObsIds);
        if (tailwaterM != null) {
            twMin = tailwaterM[0];
            twMax = tailwaterM[1];
            String origin = tailwaterObsIds.isEmpty()
                    ? "传播尾水区间（联合计算，非直接观测）"
                    : "尾水观测区间（" + tailwaterObsIds.size() + " 条）";
            ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_OBS_DOWNSTREAM_LEVEL, Vocab.ST_AVAILABLE,
                    origin + " [" + twMin + ", " + twMax + "] m"));
        } else {
            twMin = seg.downstreamInvertM();
            twMax = crownDs + props.assumedSurchargeM();
            limitations.add(Vocab.LIM_DOWNSTREAM_LEVEL_MISSING);
            assumptions.add("下游水位缺失，假设尾水区间 [" + twMin + ", " + twMax + "] m（管顶+"
                    + props.assumedSurchargeM() + "m 壅水）");
            ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_OBS_DOWNSTREAM_LEVEL, Vocab.ST_ASSUMED,
                    "下游水位缺失，假设尾水至管顶+" + props.assumedSurchargeM() + "m"));
        }

        // 6) 闸门：关闭→0；部分开启→因子区间；冲突已在情景层拆分（不取平均）
        double gateFMin = 1.0, gateFMax = 1.0;
        List<Long> gateObsIds = List.of();
        if (gate != null) {
            gateObsIds = gate.observationIds();
            if (gate.state() == GateState.CLOSED) {
                gateFMin = 0.0;
                gateFMax = 0.0;
                limitations.add(Vocab.LIM_GATE_CLOSED);
            } else if (gate.state() == GateState.PARTIAL) {
                gateFMin = 0.3;
                gateFMax = 0.7;
                limitations.add(Vocab.LIM_GATE_PARTIAL);
                assumptions.add("闸门部分开启，过流因子区间 [0.3, 0.7]");
            }
            ledger.add(new SegmentEvaluation.LedgerLine(Vocab.ITEM_OBS_GATE_STATE,
                    gate.supportedAtAsOf() ? Vocab.ST_AVAILABLE : Vocab.ST_ASSUMED,
                    "闸门 " + gate.gateCode() + " 钉定 " + gate.state()
                            + (gate.supportedAtAsOf()
                               ? "（" + gate.observationIds().size() + " 条支持观测）"
                               : "（as_of 前无支持观测入库，按条件情景钉定值计算）")));
        }
        obsIds.put("gateState", gateObsIds);

        // 7) 区间传播
        Hydraulics.ResolvedInputs inputs = new Hydraulics.ResolvedInputs(
                pv.diameterMinMm(), pv.diameterMaxMm(), siltMin, siltMax,
                pv.slopeMin(), pv.slopeMax(), nMin, nMax, twMin, twMax,
                gateFMin, gateFMax,
                new Hydraulics.SegmentGeometry(seg.upstreamInvertM(), seg.downstreamInvertM(),
                        seg.lengthM(), physicalDiameterM));
        Hydraulics.CapacityBounds bounds = Hydraulics.bounds(inputs);
        if (bounds.backwaterDominated()) {
            limitations.add(Vocab.LIM_BACKWATER_DOMINATED);
        }

        double qMinLps = bounds.qMinCms() * 1000.0;
        double qMaxLps = bounds.qMaxCms() * 1000.0;
        Double marginMin = null, marginMax = null;
        if (seg.designFlowLps() > 0) {
            marginMin = (qMinLps - seg.designFlowLps()) / seg.designFlowLps();
            marginMax = (qMaxLps - seg.designFlowLps()) / seg.designFlowLps();
        }

        evidence.put("observationIds", obsIds);
        evidence.put("assumptions", assumptions);
        evidence.put("resolvedInputs", Map.of(
                "diameterMm", List.of(pv.diameterMinMm(), pv.diameterMaxMm()),
                "siltRatio", List.of(siltMin, siltMax),
                "slope", List.of(pv.slopeMin(), pv.slopeMax()),
                "roughness", List.of(nMin, nMax),
                "tailwaterM", List.of(twMin, twMax),
                "gateFactor", List.of(gateFMin, gateFMax)));
        if (extraEvidence != null) {
            evidence.putAll(extraEvidence);
        }

        return new SegmentEvaluation(seg.id(), qMinLps, qMaxLps, marginMin, marginMax,
                bounds.contributions(), limitations, null, evidence, ledger);
    }

    private SegmentEvaluation undeterminable(Segment seg, String reason, List<String> limitations,
                                             List<SegmentEvaluation.LedgerLine> ledger,
                                             Map<String, Object> evidence) {
        return new SegmentEvaluation(seg.id(), null, null, null, null,
                List.of(), limitations, reason, evidence, ledger);
    }
}
