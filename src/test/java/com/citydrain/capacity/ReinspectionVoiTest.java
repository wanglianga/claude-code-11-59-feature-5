package com.citydrain.capacity;

import com.citydrain.capacity.domain.VoiEstimationResult;
import com.citydrain.capacity.domain.ReinspectionPlan;
import com.citydrain.capacity.domain.ReinspectionPlanItem;
import com.citydrain.capacity.service.voi.ReinspectionPlanService;
import com.citydrain.capacity.service.voi.VoiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 复检信息价值验收：价值排序、预期决策变化、低价值标记、维修覆盖排除、
 * 两条估计路线同口径对照、预算竞争与计划版本留痕。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReinspectionVoiTest {

    @Autowired VoiTestFixtures fx;
    @Autowired VoiService voiService;
    @Autowired ReinspectionPlanService planService;
    @Autowired ObjectMapper om;

    @BeforeEach
    void setup() {
        fx.build();
    }

    private VoiService.EstimateOutcome run(String method) {
        return voiService.estimate(fx.assessmentId, method, fx.candidateIds,
                fx.t(2026, 11, 10, 9, 0));
    }

    private VoiEstimationResult resultOf(List<VoiEstimationResult> results, long segmentId) {
        return results.stream().filter(r -> r.segmentId() == segmentId)
                .max((a, b) -> Double.compare(a.evi(), b.evi())).orElseThrow();
    }

    @Test
    void enumerationRanksNearThresholdAboveHighRiskEvidenceSufficient() {
        var outcome = run("AUTO");
        assertEquals("ENUMERATION", outcome.selectedMethod(), "6 候选（≤12）AUTO 选枚举法");
        var results = outcome.results();

        var nearThreshold = resultOf(results, fx.segNearThreshold());
        var highRisk = resultOf(results, fx.segHighRisk());
        assertTrue(nearThreshold.evi() > highRisk.evi(),
                "低风险但接近阈值的复检价值应高于高风险但证据充分的位置");
        assertTrue(nearThreshold.decisionChangeProb() > 0.0, "近阈值位置预期决策变化概率应为正");
        assertFalse(nearThreshold.lowValue());

        // 高风险证据充分：任何结果都无法改变 REMEDIATE 决定 → 低价值
        assertTrue(highRisk.lowValue());
        assertTrue(highRisk.lowValueReasonsJson().contains("DECISION_IMMUTABLE"));
        assertEquals(0.0, highRisk.decisionChangeProb(), 1e-9);
    }

    @Test
    void repairedAcceptedCoverageIsExcludedAndNotRecommended() {
        var results = run("ENUMERATION").results();
        var repaired = results.stream()
                .filter(r -> r.segmentId() == fx.segRepaired()).findFirst().orElseThrow();
        assertTrue(repaired.excludedRepaired(), "完整验收维修覆盖区域不重复推荐");
        assertEquals(0.0, repaired.evi(), 1e-9);
        assertTrue(repaired.lowValueReasonsJson().contains("REPAIRED_ACCEPTED_COVERAGE"));
    }

    @Test
    void insufficientResolutionLowersValueOnAmbiguousLocation() {
        var results = run("ENUMERATION").results();
        var sonar = results.stream().filter(r -> r.candidateId() == fx.candidateIds.get(2)).findFirst().orElseThrow();
        var cctv = results.stream().filter(r -> r.candidateId() == fx.candidateIds.get(3)).findFirst().orElseThrow();
        assertTrue(sonar.resolutionPenalty() < cctv.resolutionPenalty(),
                "20mm 分辨率无法区分 20mm 间距相邻缺陷，降权应更重");
        assertTrue(sonar.evi() <= cctv.evi(), "分辨率不足的同位置候选信息价值不高于高精度方法");
    }

    @Test
    void sensitivityRouteProducesSameScaleEviAndFlagsDiscreteLimitation() throws Exception {
        var enumeration = run("ENUMERATION");
        var sensitivity = run("SENSITIVITY");
        assertEquals("SENSITIVITY", sensitivity.selectedMethod());
        for (VoiEstimationResult r : sensitivity.results()) {
            assertTrue(r.evi() >= 0 && r.evi() <= 1, "两路线 evi 同一口径 [0,1]");
            if (r.excludedRepaired()) {
                continue; // 维修覆盖排除行走零值明细，不适用敏感度字段
            }
            assertNotNull(r.sensitivityDetailJson());
            JsonNode detail = om.readTree(r.sensitivityDetailJson());
            assertTrue(detail.has("mayMissDiscreteJumps"), "敏感度法须显式标注离散阈值跳变局限");
            assertTrue(detail.has("boundaryCrossings"));
        }
        // 两路线独立产出：敏感度批次不依赖枚举批次存在（本测试中两批次各自持久化）
        assertNotEquals(enumeration.estimation().id(), sensitivity.estimation().id());
        // 近阈值位置在敏感度法下同样不是低价值
        assertFalse(resultOf(sensitivity.results(), fx.segNearThreshold()).lowValue());
    }

    @Test
    void autoSwitchesToSensitivityForLargeCandidateSet() {
        // 追加候选至 13 个（> auto-enumeration-max-candidates=12）→ AUTO 改走敏感度法
        for (int i = 0; i < 7; i++) {
            long extra = fx.nvId; // 仅借用夹具网络，候选挂在 segA
            voiService.registerCandidate(fx.prefix + "-EXTRA-" + i, fx.segHighRisk(), "QV",
                    8, 1.0, 7000 + i, om.createArrayNode().add("功能性缺陷"),
                    om.createObjectNode().put("startM", 0).put("endM", 100),
                    true, false, "追加候选");
        }
        var outcome = voiService.estimate(fx.assessmentId, "AUTO", null, fx.t(2026, 11, 11, 9, 0));
        assertEquals("SENSITIVITY", outcome.selectedMethod(), "候选超过 12 个时 AUTO 选敏感度法");
        assertTrue(outcome.selectionReason().contains("AUTO→SENSITIVITY"));
    }

    @Test
    void planGenerationAppliesConstraintsAndKeepsVersionHistory() {
        var estimation = run("ENUMERATION");
        // v1：预算 2 万 + 仅可达 + 雨季窗口（11 月，当前月份，触发雨季禁入）
        var v1 = planService.generate("PLAN-" + fx.prefix, estimation.estimation().id(),
                20000, true, "{\"start\":\"11-01\",\"end\":\"11-30\"}");
        var v1Items = v1.items();
        assertTrue(v1Items.stream().anyMatch(i -> i.reason().equals("SUPPLIER_UNREACHABLE")),
                "SONAR 候选供应商不可达应落选");
        assertTrue(v1Items.stream().anyMatch(i -> i.reason().equals("RAINY_SEASON_BLOCKED")),
                "雨季禁入候选应落选");
        assertTrue(v1Items.stream().anyMatch(i -> i.reason().equals("REPAIRED_ACCEPTED")));
        assertTrue(v1Items.stream().anyMatch(ReinspectionPlanItem::selected));
        assertTrue(v1.plan().totalCost() <= 20000 + 1e-9, "入选总成本不得超预算");

        // v2：放宽预算与供应商、取消雨季 → 新版本，supersedes 指向 v1，v1 保留
        var v2 = planService.generate("PLAN-" + fx.prefix, estimation.estimation().id(),
                60000, false, null);
        assertEquals(2, v2.plan().planVersion());
        assertEquals(v1.plan().id(), v2.plan().supersedesPlanId());
        List<ReinspectionPlan> history = planService.listVersions("PLAN-" + fx.prefix);
        assertEquals(2, history.size(), "旧计划版本保留");
        assertEquals(20000, history.get(0).budget(), 1e-9);
        assertEquals(60000, history.get(1).budget(), 1e-9);
        // 放宽后原不可达/雨季候选可参与组合
        assertTrue(v2.items().stream().noneMatch(i -> i.reason().equals("SUPPLIER_UNREACHABLE")));
        assertTrue(v2.items().stream().noneMatch(i -> i.reason().equals("RAINY_SEASON_BLOCKED")));
    }

    @Test
    void budgetCompetitionDropsLowerEfficiencyCandidate() {
        var estimation = run("ENUMERATION");
        // 极小预算：仅够一个低成本候选，背包按 evi/成本权衡，至少一个候选因预算竞争落选
        var outcome = planService.generate("PLAN-BUDGET-" + fx.prefix, estimation.estimation().id(),
                5000, true, null);
        assertTrue(outcome.items().stream().anyMatch(i -> i.reason().equals("BUDGET_COMPETITION")),
                "预算不足时应出现预算竞争落选");
        double selectedCost = outcome.items().stream().filter(ReinspectionPlanItem::selected)
                .mapToDouble(ReinspectionPlanItem::cost).sum();
        assertTrue(selectedCost <= 5000 + 1e-9);
    }

    @Test
    void estimationIsImmutableAndReplayable() {
        var first = run("ENUMERATION");
        var second = run("ENUMERATION");
        // 同 assessment+method+asOf 幂等：重放返回同一批次（结果不可变，按相同输入可复算）
        assertEquals(first.estimation().id(), second.estimation().id());
        var r1 = voiService.getResults(first.estimation().id());
        var r2 = voiService.getResults(second.estimation().id());
        assertEquals(r1.size(), r2.size());
        for (int i = 0; i < r1.size(); i++) {
            assertEquals(r1.get(i).evi(), r2.get(i).evi(), 1e-12, "相同输入 evi 逐位一致");
            assertEquals(r1.get(i).decisionChangeProb(), r2.get(i).decisionChangeProb(), 1e-12);
        }
    }
}
