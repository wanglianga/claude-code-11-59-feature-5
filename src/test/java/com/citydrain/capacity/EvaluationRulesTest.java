package com.citydrain.capacity;

import com.citydrain.capacity.domain.EvalMethod;
import com.citydrain.capacity.domain.GateState;
import com.citydrain.capacity.domain.Scenario;
import com.citydrain.capacity.domain.Segment;
import com.citydrain.capacity.domain.Vocab;
import com.citydrain.capacity.repo.SegmentRepo;
import com.citydrain.capacity.service.ComputationService;
import com.citydrain.capacity.service.ScenarioAssembler;
import com.citydrain.capacity.service.eval.EvalContext;
import com.citydrain.capacity.service.eval.IntervalPropagationEvaluator;
import com.citydrain.capacity.service.eval.SegmentEvaluation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static com.citydrain.capacity.TestFixtures.t;
import static org.junit.jupiter.api.Assertions.*;

/** 评估规则：缺失扩大、闸门条件、回水、不可判定。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EvaluationRulesTest {

    @Autowired TestFixtures fx;
    @Autowired SegmentRepo segmentRepo;
    @Autowired IntervalPropagationEvaluator intervalEvaluator;
    @Autowired ScenarioAssembler assembler;
    @Autowired ComputationService computationService;

    private EvalContext ctx(Map<String, EvalContext.GatePin> gates) {
        return new EvalContext(t(2026, 7, 14, 2, 0), t(2026, 7, 14, 8, 0),
                t(2026, 7, 20, 0, 0), gates, "TB1");
    }

    @BeforeEach
    void setup() {
        fx.baseNetwork();
    }

    @Test
    void missingRoughnessWidensIntervalAndFlags() {
        fx.param(fx.segA, 1, 990.0, 1000.0, null, null, t(2026, 1, 1, 0, 0), t(2026, 1, 5, 10, 0));
        fx.obs("DOWNSTREAM_LEVEL", "BOUNDARY", "TN2", 5.30, null, t(2026, 7, 14, 4, 0), t(2026, 7, 14, 9, 0));
        Segment seg = segmentRepo.findById(fx.segA).orElseThrow();
        SegmentEvaluation ev = intervalEvaluator.evaluateSegment(seg, ctx(Map.of()));
        assertTrue(ev.limitations().contains(Vocab.LIM_ROUGHNESS_MISSING));
        assertNotNull(ev.qMinLps());
        // 与有糙率声明的对照：缺失时区间更宽
        fx.param(fx.segB, 1, 990.0, 1000.0, 0.012, 0.014, t(2026, 1, 1, 0, 0), t(2026, 1, 5, 10, 0));
        fx.obs("DOWNSTREAM_LEVEL", "BOUNDARY", "TB1", 5.30, null, t(2026, 7, 14, 4, 0), t(2026, 7, 14, 9, 0));
        Segment segB = segmentRepo.findById(fx.segB).orElseThrow();
        SegmentEvaluation evB = intervalEvaluator.evaluateSegment(segB, ctx(Map.of()));
        assertFalse(evB.limitations().contains(Vocab.LIM_ROUGHNESS_MISSING));
        assertTrue(ev.qMaxLps() - ev.qMinLps() > evB.qMaxLps() - evB.qMinLps(),
                "糙率缺失 → 区间更宽");
    }

    @Test
    void missingDownstreamLevelWidensAndFlags() {
        fx.param(fx.segA, 1, 990.0, 1000.0, 0.012, 0.014, t(2026, 1, 1, 0, 0), t(2026, 1, 5, 10, 0));
        Segment seg = segmentRepo.findById(fx.segA).orElseThrow();
        SegmentEvaluation ev = intervalEvaluator.evaluateSegment(seg, ctx(Map.of()));
        assertTrue(ev.limitations().contains(Vocab.LIM_DOWNSTREAM_LEVEL_MISSING));
        assertTrue(ev.ledger().stream().anyMatch(l -> l.item().equals(Vocab.ITEM_OBS_DOWNSTREAM_LEVEL)
                && l.status().equals(Vocab.ST_ASSUMED)));
    }

    @Test
    void gateClosedYieldsZeroCapacityNotAveraged() {
        fx.param(fx.segA, 1, 990.0, 1000.0, 0.012, 0.014, t(2026, 1, 1, 0, 0), t(2026, 1, 5, 10, 0));
        Segment seg = segmentRepo.findById(fx.segA).orElseThrow();
        EvalContext closed = ctx(Map.of("S-A",
                new EvalContext.GatePin("G-A", GateState.CLOSED, List.of(1L), true)));
        SegmentEvaluation ev = intervalEvaluator.evaluateSegment(seg, closed);
        assertEquals(0.0, ev.qMinLps(), 1e-9);
        assertEquals(0.0, ev.qMaxLps(), 1e-9);
        assertTrue(ev.limitations().contains(Vocab.LIM_GATE_CLOSED));
    }

    @Test
    void gateConflictSplitsConditionalScenarios() {
        fx.param(fx.segA, 1, 990.0, 1000.0, 0.012, 0.014, t(2026, 1, 1, 0, 0), t(2026, 1, 5, 10, 0));
        fx.obs("GATE_STATE", "GATE", "G-A", null, "OPEN", t(2026, 7, 14, 2, 30), t(2026, 7, 14, 9, 0));
        fx.obs("GATE_STATE", "GATE", "G-A", null, "CLOSED", t(2026, 7, 14, 3, 10), t(2026, 7, 14, 9, 0));
        var result = assembler.assemble(fx.eventId, fx.nvId, List.of(fx.segA, fx.segB), "TB1",
                List.of(new ScenarioAssembler.GateSpec("G-A", "S-A")));
        assertEquals(2, result.scenarios().size(), "冲突 → 两个条件情景");
        assertFalse(result.conflicts().isEmpty());
        assertTrue(result.scenarios().stream().anyMatch(s -> s.conditionLabel().contains("OPEN")));
        assertTrue(result.scenarios().stream().anyMatch(s -> s.conditionLabel().contains("CLOSED")));
    }

    @Test
    void backwaterReducesLowerBound() {
        fx.param(fx.segA, 1, 990.0, 1000.0, 0.012, 0.014, t(2026, 1, 1, 0, 0), t(2026, 1, 5, 10, 0));
        // 尾水 6.50 超过上游水头（5.20+~0.96≈6.16）→ 下界归零
        fx.obs("DOWNSTREAM_LEVEL", "BOUNDARY", "TN2", 6.50, null, t(2026, 7, 14, 4, 0), t(2026, 7, 14, 9, 0));
        Segment seg = segmentRepo.findById(fx.segA).orElseThrow();
        SegmentEvaluation ev = intervalEvaluator.evaluateSegment(seg, ctx(Map.of()));
        assertEquals(0.0, ev.qMinLps(), 1e-9);
        assertTrue(ev.limitations().contains(Vocab.LIM_BACKWATER_DOMINATED));
    }

    @Test
    void noParameterVersionIsUndeterminable() {
        // 版本 2026-06 才生效 → asOf=2026-03 不可判定
        fx.param(fx.segA, 1, 990.0, 1000.0, 0.012, 0.014, t(2026, 6, 1, 0, 0), t(2026, 1, 5, 10, 0));
        Segment seg = segmentRepo.findById(fx.segA).orElseThrow();
        EvalContext march = new EvalContext(t(2026, 3, 1, 0, 0), t(2026, 3, 2, 0, 0),
                t(2026, 3, 1, 12, 0), Map.of(), "TB1");
        SegmentEvaluation ev = intervalEvaluator.evaluateSegment(seg, march);
        assertNull(ev.qMinLps());
        assertEquals(Vocab.REASON_NO_PARAM_VERSION, ev.undeterminableReason());
    }

    @Test
    void misalignedFlowRejectedFromCalibration() throws Exception {
        fx.param(fx.segA, 1, 990.0, 1000.0, 0.012, 0.014, t(2026, 1, 1, 0, 0), t(2026, 1, 5, 10, 0));
        // 对齐三元组 ×3 + 一条未对齐流量
        for (int i = 0; i < 3; i++) {
            var ts = t(2026, 7, 14, 3 + i, 0);
            fx.obs("FLOW", "SEGMENT", "S-A", 1000.0, null, ts, t(2026, 7, 14, 9, 0));
            fx.obs("WATER_LEVEL", "NODE", "TN1", 6.20, null, ts, t(2026, 7, 14, 9, 0));
            fx.obs("WATER_LEVEL", "NODE", "TN2", 6.00, null, ts, t(2026, 7, 14, 9, 0));
        }
        fx.obs("FLOW", "SEGMENT", "S-A", 1500.0, null, t(2026, 7, 14, 3, 45), t(2026, 7, 14, 9, 0));
        var scenario = assembler.assemble(fx.eventId, fx.nvId, List.of(fx.segA), "TB1", List.of())
                .scenarios().get(0);
        var outcome = computationService.run(scenario.id(), EvalMethod.CALIBRATED_ENSEMBLE,
                t(2026, 7, 20, 0, 0));
        var results = computationService.getResults(outcome.run().id());
        assertEquals(1, results.size());
        var evidence = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(results.get(0).evidenceJson()).path("calibration");
        assertEquals(3, evidence.path("sampleCount").asInt(), "3 条对齐样本");
        assertEquals(1, evidence.path("rejectedCount").asInt(), "未对齐样本被拒绝");
    }
}
