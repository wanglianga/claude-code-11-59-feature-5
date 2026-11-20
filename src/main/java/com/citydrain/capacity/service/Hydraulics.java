package com.citydrain.capacity.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区间水力计算核心（纯函数，确定性、可逐项复核）。
 * 圆管满流曼宁公式：Q = C · D_eff^(8/3) · √S_eff / n，C = π / 4^(5/3)。
 * 两条评估路线各自解析输入区间后调用本核心；核心本身不构成任一路线的预处理或核验。
 */
public final class Hydraulics {

    private Hydraulics() {}

    public static final double C = Math.PI / Math.pow(4.0, 5.0 / 3.0);

    /** 管段固定几何（与参数版本无关的量）。 */
    public record SegmentGeometry(double usInvertM, double dsInvertM, double lengthM,
                                  double physicalDiameterM) {}

    /**
     * 一次评估所需的全部已解析区间输入（单位：直径 mm，水位/管底 m，坡度小数，糙率 n）。
     */
    public record ResolvedInputs(
            double dMinMm, double dMaxMm,
            double siltMin, double siltMax,
            double sMin, double sMax,
            double nMin, double nMax,
            double twMinM, double twMaxM,
            double gateFactorMin, double gateFactorMax,
            SegmentGeometry geometry
    ) {}

    /** 主导不确定参数贡献。 */
    public record Contribution(String parameter, double contributionPct) {}

    /** 区间评估输出。 */
    public record CapacityBounds(double qMinCms, double qMaxCms, boolean backwaterDominated,
                                 List<Contribution> contributions) {}

    /** 满管流量 m³/s。 */
    public static double manningFullCms(double dEffM, double sEff, double n) {
        if (sEff <= 0 || dEffM <= 0 || n <= 0) return 0.0;
        return C * Math.pow(dEffM, 8.0 / 3.0) * Math.sqrt(sEff) / n;
    }

    /** 淤积后的有效等效直径（面积等效）：D_eff = D·√(1-淤积率)。 */
    public static double effectiveDiameterM(double diameterM, double siltRatio) {
        return diameterM * Math.sqrt(Math.max(0.0, 1.0 - siltRatio));
    }

    /**
     * 有效坡度：下游水位不超过管顶→自由出流取底坡；否则按出口淹没取水头差/管长。
     */
    public static double effectiveSlope(double bedSlope, double tailwaterM,
                                        double usHeadM, double crownDsM, double lengthM) {
        if (tailwaterM <= crownDsM) return bedSlope;
        return Math.max(0.0, (usHeadM - tailwaterM) / lengthM);
    }

    /** 单点流量：给定一组参数值求满管可通过流量（m³/s）。 */
    public static double qOf(double dMm, double silt, double slope, double n, double twM,
                             SegmentGeometry g, double gateFactor) {
        double dEff = effectiveDiameterM(dMm / 1000.0, silt);
        double crownDs = g.dsInvertM() + g.physicalDiameterM();
        double usHead = g.usInvertM() + dEff;
        double sEff = effectiveSlope(slope, twM, usHead, crownDs, g.lengthM());
        return manningFullCms(dEff, sEff, n) * gateFactor;
    }

    /**
     * 区间传播：上界取有利端（D大、淤积小、坡陡、糙率小、下游水位低、闸门因子大），下界取相反端。
     */
    public static CapacityBounds bounds(ResolvedInputs in) {
        double qMax = qOf(in.dMaxMm(), in.siltMin(), in.sMax(), in.nMin(), in.twMinM(),
                in.geometry(), in.gateFactorMax());
        double qMin = qOf(in.dMinMm(), in.siltMax(), in.sMin(), in.nMax(), in.twMaxM(),
                in.geometry(), in.gateFactorMin());
        // 下界是否因回水归零（下界尾水超过上游水头）
        double dEffMin = effectiveDiameterM(in.dMinMm() / 1000.0, in.siltMax());
        double crownDs = in.geometry().dsInvertM() + in.geometry().physicalDiameterM();
        boolean backwaterDominated = in.twMaxM() > crownDs
                && (in.geometry().usInvertM() + dEffMin - in.twMaxM()) <= 0;
        return new CapacityBounds(qMin, qMax, backwaterDominated, contributions(in));
    }

    /**
     * 主导不确定参数：其余参数取中值，分别将每个参数在其区间内扰动，
     * 按 |Q(高)-Q(低)| 归一化得到贡献占比，降序输出。
     */
    public static List<Contribution> contributions(ResolvedInputs in) {
        SegmentGeometry g = in.geometry();
        double dMid = (in.dMinMm() + in.dMaxMm()) / 2;
        double siltMid = (in.siltMin() + in.siltMax()) / 2;
        double sMid = (in.sMin() + in.sMax()) / 2;
        double nMid = (in.nMin() + in.nMax()) / 2;
        double twMid = (in.twMinM() + in.twMaxM()) / 2;
        double gateMid = (in.gateFactorMin() + in.gateFactorMax()) / 2;

        Map<String, Double> deltas = new LinkedHashMap<>();
        deltas.put("DIAMETER", Math.abs(
                qOf(in.dMaxMm(), siltMid, sMid, nMid, twMid, g, gateMid)
                        - qOf(in.dMinMm(), siltMid, sMid, nMid, twMid, g, gateMid)));
        deltas.put("SILTATION", Math.abs(
                qOf(dMid, in.siltMax(), sMid, nMid, twMid, g, gateMid)
                        - qOf(dMid, in.siltMin(), sMid, nMid, twMid, g, gateMid)));
        deltas.put("SLOPE", Math.abs(
                qOf(dMid, siltMid, in.sMax(), nMid, twMid, g, gateMid)
                        - qOf(dMid, siltMid, in.sMin(), nMid, twMid, g, gateMid)));
        deltas.put("ROUGHNESS", Math.abs(
                qOf(dMid, siltMid, sMid, in.nMin(), twMid, g, gateMid)
                        - qOf(dMid, siltMid, sMid, in.nMax(), twMid, g, gateMid)));
        deltas.put("DOWNSTREAM_LEVEL", Math.abs(
                qOf(dMid, siltMid, sMid, nMid, in.twMinM(), g, gateMid)
                        - qOf(dMid, siltMid, sMid, nMid, in.twMaxM(), g, gateMid)));
        if (in.gateFactorMax() > in.gateFactorMin()) {
            deltas.put("GATE_STATE", Math.abs(
                    qOf(dMid, siltMid, sMid, nMid, twMid, g, in.gateFactorMax())
                            - qOf(dMid, siltMid, sMid, nMid, twMid, g, in.gateFactorMin())));
        }

        double total = deltas.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) return List.of();
        double t = total;
        List<Contribution> list = new ArrayList<>();
        deltas.forEach((k, v) -> list.add(new Contribution(k, round1(v / t * 100.0))));
        list.sort(Comparator.comparingDouble(Contribution::contributionPct).reversed());
        return list;
    }

    public static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
