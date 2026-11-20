package com.citydrain.capacity;

import com.citydrain.capacity.service.Hydraulics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 水力核心纯函数测试。 */
class HydraulicsTest {

    private static final Hydraulics.SegmentGeometry G =
            new Hydraulics.SegmentGeometry(5.20, 5.00, 100.0, 1.0);

    @Test
    void manningFullFlowMatchesHandCalc() {
        // Q = C·D^(8/3)·√S/n，C=π/4^(5/3)≈0.311686；D=1, S=0.002, n=0.013 → ≈1.07252 m³/s
        double q = Hydraulics.manningFullCms(1.0, 0.002, 0.013);
        assertEquals(1.07252, q, 0.001);
    }

    @Test
    void effectiveDiameterReducesWithSilt() {
        assertEquals(0.9, Hydraulics.effectiveDiameterM(1.0, 0.19), 1e-9);
        assertEquals(1.0, Hydraulics.effectiveDiameterM(1.0, 0.0), 1e-9);
    }

    @Test
    void effectiveSlopeFreeOutflowVsSubmerged() {
        // 尾水低于管顶（5.00+1.0=6.00）→ 底坡
        assertEquals(0.002, Hydraulics.effectiveSlope(0.002, 5.5, 6.2, 6.0, 100.0), 1e-12);
        // 尾水高于管顶 → 水头差/管长 = (6.2-6.3)/100 → 0（为负取 0）
        assertEquals(0.0, Hydraulics.effectiveSlope(0.002, 6.3, 6.2, 6.0, 100.0), 1e-12);
        // (6.2-6.1)/100 = 0.001
        assertEquals(0.001, Hydraulics.effectiveSlope(0.002, 6.1, 6.2, 6.0, 100.0), 1e-12);
    }

    @Test
    void widerRoughnessIntervalWidensCapacityInterval() {
        Hydraulics.ResolvedInputs narrow = new Hydraulics.ResolvedInputs(
                990, 1000, 0.0, 0.05, 0.0019, 0.0021, 0.012, 0.014, 5.0, 5.5, 1.0, 1.0, G);
        Hydraulics.ResolvedInputs wide = new Hydraulics.ResolvedInputs(
                990, 1000, 0.0, 0.05, 0.0019, 0.0021, 0.009, 0.035, 5.0, 5.5, 1.0, 1.0, G);
        Hydraulics.CapacityBounds n = Hydraulics.bounds(narrow);
        Hydraulics.CapacityBounds w = Hydraulics.bounds(wide);
        assertTrue(w.qMinCms() < n.qMinCms(), "糙率区间变宽 → 下界更低");
        assertTrue(w.qMaxCms() > n.qMaxCms(), "糙率区间变宽 → 上界更高");
    }

    @Test
    void contributionsSumToHundredAndRank() {
        Hydraulics.ResolvedInputs in = new Hydraulics.ResolvedInputs(
                900, 1000, 0.0, 0.2, 0.0015, 0.0025, 0.009, 0.035, 5.0, 6.5, 1.0, 1.0, G);
        var cs = Hydraulics.contributions(in);
        double sum = cs.stream().mapToDouble(Hydraulics.Contribution::contributionPct).sum();
        assertEquals(100.0, sum, 0.5);
        // 糙率区间最宽 → 主导
        assertEquals("ROUGHNESS", cs.get(0).parameter());
    }

    @Test
    void backwaterDominatedWhenTailwaterExceedsUpstreamHead() {
        Hydraulics.ResolvedInputs in = new Hydraulics.ResolvedInputs(
                990, 1000, 0.0, 0.05, 0.0019, 0.0021, 0.012, 0.014, 5.0, 6.5, 1.0, 1.0, G);
        var b = Hydraulics.bounds(in);
        assertTrue(b.backwaterDominated());
        assertEquals(0.0, b.qMinCms(), 1e-12);
    }
}
