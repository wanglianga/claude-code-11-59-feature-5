package com.citydrain.capacity.service.voi;

import com.citydrain.capacity.domain.ReinspectionCandidate;
import com.citydrain.capacity.domain.ReinspectionPlan;
import com.citydrain.capacity.domain.ReinspectionPlanItem;
import com.citydrain.capacity.domain.VoiEstimation;
import com.citydrain.capacity.domain.VoiEstimationResult;
import com.citydrain.capacity.domain.Vocab;
import com.citydrain.capacity.repo.ReinspectionCandidateRepo;
import com.citydrain.capacity.repo.ReinspectionPlanRepo;
import com.citydrain.capacity.repo.VoiEstimationRepo;
import com.citydrain.capacity.service.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复检计划版本编排：在预算/供应商可达性/雨季禁入约束下做多点组合选择。
 * 组合优化用 0/1 背包（容量=预算，价值=evi），解决多点预算竞争；
 * 约束（预算、供应商、雨季）变化后生成新计划版本，旧计划与当时约束快照保留、不可变。
 */
@Service
public class ReinspectionPlanService {

    private final ReinspectionPlanRepo planRepo;
    private final VoiEstimationRepo estimationRepo;
    private final ReinspectionCandidateRepo candidateRepo;
    private final ObjectMapper objectMapper;

    public ReinspectionPlanService(ReinspectionPlanRepo planRepo, VoiEstimationRepo estimationRepo,
                                   ReinspectionCandidateRepo candidateRepo, ObjectMapper objectMapper) {
        this.planRepo = planRepo;
        this.estimationRepo = estimationRepo;
        this.candidateRepo = candidateRepo;
        this.objectMapper = objectMapper;
    }

    public record PlanOutcome(ReinspectionPlan plan, List<ReinspectionPlanItem> items) {}

    /**
     * @param rainySeasonWindowJson 雨季窗口 {"start":"06-01","end":"09-30"}（月-日），null 表示不禁入
     */
    @Transactional
    public PlanOutcome generate(String planCode, long estimationId, double budget,
                                boolean supplierOnlyReachable, String rainySeasonWindowJson) {
        VoiEstimation estimation = estimationRepo.findEstimationById(estimationId)
                .orElseThrow(() -> ApiException.notFound("VOI 估计批次不存在: " + estimationId));
        if (budget < 0) {
            throw ApiException.badRequest("预算不可为负");
        }
        List<VoiEstimationResult> results = estimationRepo.listResults(estimationId);
        if (results.isEmpty()) {
            throw ApiException.unprocessable("估计批次无结果，无法生成计划");
        }
        Map<Long, ReinspectionCandidate> candidates = new LinkedHashMap<>();
        candidateRepo.listAll().forEach(c -> candidates.put(c.id(), c));

        MonthDay[] rainyWindow = parseRainyWindow(rainySeasonWindowJson);
        boolean rainyNow = rainyWindow != null && withinWindow(MonthDay.from(LocalDate.now()), rainyWindow);

        // 1) 约束分类：不可达/雨季禁入/低价值/已维修覆盖直接落选并记录原因，其余进入预算竞争
        List<Long> eligible = new ArrayList<>();
        Map<Long, String> exclusionReason = new LinkedHashMap<>();
        Map<Long, String> exclusionDetail = new LinkedHashMap<>();
        for (VoiEstimationResult r : results) {
            ReinspectionCandidate c = candidates.get(r.candidateId());
            if (c == null) {
                throw ApiException.unprocessable("候选不存在: " + r.candidateId());
            }
            if (r.excludedRepaired()) {
                exclusionReason.put(r.candidateId(), Vocab.PI_REPAIRED);
                exclusionDetail.put(r.candidateId(), "已维修且完整验收覆盖，不重复推荐");
            } else if (supplierOnlyReachable && !c.supplierReachable()) {
                exclusionReason.put(r.candidateId(), Vocab.PI_SUPPLIER);
                exclusionDetail.put(r.candidateId(), "供应商当前不可达");
            } else if (rainyNow && c.rainySeasonBlocked()) {
                exclusionReason.put(r.candidateId(), Vocab.PI_RAINY);
                exclusionDetail.put(r.candidateId(), "雨季禁入窗口 " + rainySeasonWindowJson + " 内不可作业");
            } else if (r.lowValue()) {
                exclusionReason.put(r.candidateId(), Vocab.PI_LOW_VALUE);
                exclusionDetail.put(r.candidateId(), "低价值检查：" + r.lowValueReasonsJson());
            } else {
                eligible.add(r.candidateId());
            }
        }

        // 2) 0/1 背包：成本按分取整为整数重量，evi 放大 1e3 取整为整数价值
        Map<Long, VoiEstimationResult> resultByCandidate = new LinkedHashMap<>();
        results.forEach(r -> resultByCandidate.put(r.candidateId(), r));
        int capacity = (int) Math.round(budget * 100.0);
        int[] weight = new int[eligible.size()];
        int[] value = new int[eligible.size()];
        for (int i = 0; i < eligible.size(); i++) {
            ReinspectionCandidate c = candidates.get(eligible.get(i));
            weight[i] = (int) Math.round(c.cost() * 100.0);
            value[i] = (int) Math.round(resultByCandidate.get(eligible.get(i)).evi() * 1000.0);
        }
        boolean[] chosen = knapsack(weight, value, capacity);

        // 3) 组装明细
        List<ReinspectionPlanItem> items = new ArrayList<>();
        double totalCost = 0.0;
        double totalEvi = 0.0;
        List<Long> selectedIds = new ArrayList<>();
        for (int i = 0; i < eligible.size(); i++) {
            selectedIds.add(chosen[i] ? eligible.get(i) : null);
        }
        for (VoiEstimationResult r : results) {
            long candidateId = r.candidateId();
            ReinspectionCandidate c = candidates.get(candidateId);
            boolean selected = selectedIds.contains(candidateId);
            String reason;
            String detail = null;
            if (exclusionReason.containsKey(candidateId)) {
                reason = exclusionReason.get(candidateId);
                detail = exclusionDetail.get(candidateId);
            } else if (selected) {
                reason = Vocab.PI_SELECTED;
                totalCost += c.cost();
                totalEvi += r.evi();
            } else {
                reason = Vocab.PI_BUDGET;
                detail = "evi/成本比或预算容量约束下未进入最优组合（0/1 背包）";
            }
            items.add(new ReinspectionPlanItem(0, 0, candidateId, r.segmentId(), selected,
                    r.evi(), c.cost(), r.expectedRiskReductionM(), r.decisionChangeProb(),
                    reason, detail));
        }

        // 4) 版本留痕：同 planCode 下新版本，supersedes 指向最近版本，旧计划保留
        int version = planRepo.nextVersionNo(planCode);
        Long supersedes = planRepo.listByPlanCode(planCode).stream()
                .map(ReinspectionPlan::id).reduce((a, b) -> b).orElse(null);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("budget", budget);
        snapshot.put("supplierOnlyReachable", supplierOnlyReachable);
        snapshot.put("rainySeasonWindow", rainySeasonWindowJson);
        snapshot.put("rainyActiveAtGeneration", rainyNow);
        snapshot.put("estimationId", estimationId);
        snapshot.put("estimationMethod", estimation.method());
        snapshot.put("generatedAt", OffsetDateTime.now().toString());
        snapshot.put("excludedCounts", countByReason(items));

        ReinspectionPlan plan = new ReinspectionPlan(0, planCode, version, estimationId, budget,
                supplierOnlyReachable, rainySeasonWindowJson,
                Math.round(totalCost * 100.0) / 100.0, Math.round(totalEvi * 1000.0) / 1000.0,
                writeJson(snapshot), supersedes, null);
        long planId = planRepo.insertPlan(plan);
        for (ReinspectionPlanItem item : items) {
            planRepo.insertItem(new ReinspectionPlanItem(0, planId, item.candidateId(), item.segmentId(),
                    item.selected(), item.evi(), item.cost(), item.expectedRiskReductionM(),
                    item.decisionChangeProb(), item.reason(), item.detail()));
        }
        return new PlanOutcome(planRepo.findById(planId).orElseThrow(),
                planRepo.listItems(planId));
    }

    /** 0/1 背包 DP，回溯返回每项是否入选；平局优先编号靠前（evi 排序已在前）。 */
    private boolean[] knapsack(int[] weight, int[] value, int capacity) {
        int n = weight.length;
        int[][] dp = new int[n + 1][capacity + 1];
        for (int i = 1; i <= n; i++) {
            for (int w = 0; w <= capacity; w++) {
                dp[i][w] = dp[i - 1][w];
                if (weight[i - 1] <= w) {
                    dp[i][w] = Math.max(dp[i][w], dp[i - 1][w - weight[i - 1]] + value[i - 1]);
                }
            }
        }
        boolean[] chosen = new boolean[n];
        int w = capacity;
        for (int i = n; i >= 1; i--) {
            if (dp[i][w] != dp[i - 1][w]) {
                chosen[i - 1] = true;
                w -= weight[i - 1];
            }
        }
        return chosen;
    }

    private Map<String, Long> countByReason(List<ReinspectionPlanItem> items) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ReinspectionPlanItem i : items) {
            counts.merge(i.reason(), 1L, Long::sum);
        }
        return counts;
    }

    private MonthDay[] parseRainyWindow(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            MonthDay start = MonthDay.parse("--" + node.path("start").asText());
            MonthDay end = MonthDay.parse("--" + node.path("end").asText());
            return new MonthDay[]{start, end};
        } catch (Exception e) {
            throw ApiException.badRequest("雨季窗口格式须为 {\"start\":\"MM-dd\",\"end\":\"MM-dd\"}");
        }
    }

    private boolean withinWindow(MonthDay now, MonthDay[] window) {
        if (window[0].isBefore(window[1]) || window[0].equals(window[1])) {
            return (now.isAfter(window[0]) || now.equals(window[0]))
                    && (now.isBefore(window[1]) || now.equals(window[1]));
        }
        // 跨年窗口（如 11-01 → 03-31）
        return now.compareTo(window[0]) >= 0 || now.compareTo(window[1]) <= 0;
    }

    public List<ReinspectionPlan> listVersions(String planCode) {
        List<ReinspectionPlan> plans = planRepo.listByPlanCode(planCode);
        if (plans.isEmpty()) {
            throw ApiException.notFound("复检计划不存在: " + planCode);
        }
        return plans;
    }

    public ReinspectionPlan getPlan(long planId) {
        return planRepo.findById(planId)
                .orElseThrow(() -> ApiException.notFound("复检计划版本不存在: " + planId));
    }

    public List<ReinspectionPlanItem> getItems(long planId) {
        getPlan(planId);
        return planRepo.listItems(planId);
    }

    public List<ReinspectionPlan> listAllPlans() {
        return planRepo.listAll();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw ApiException.badRequest("计划快照序列化失败: " + e.getMessage());
        }
    }
}
