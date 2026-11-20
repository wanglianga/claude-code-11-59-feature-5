package com.citydrain.capacity.service;

import com.citydrain.capacity.domain.ComputationRun;
import com.citydrain.capacity.domain.EvalMethod;
import com.citydrain.capacity.domain.GateState;
import com.citydrain.capacity.domain.HydroEvent;
import com.citydrain.capacity.domain.CapacityResult;
import com.citydrain.capacity.domain.CoverageEntry;
import com.citydrain.capacity.domain.Observation;
import com.citydrain.capacity.domain.Scenario;
import com.citydrain.capacity.domain.Segment;
import com.citydrain.capacity.domain.Vocab;
import com.citydrain.capacity.repo.EventRepo;
import com.citydrain.capacity.repo.ObservationRepo;
import com.citydrain.capacity.repo.RunRepo;
import com.citydrain.capacity.repo.ScenarioRepo;
import com.citydrain.capacity.repo.SegmentRepo;
import com.citydrain.capacity.service.eval.CalibratedEnsembleEvaluator;
import com.citydrain.capacity.service.eval.EvalContext;
import com.citydrain.capacity.service.eval.IntervalPropagationEvaluator;
import com.citydrain.capacity.service.eval.SegmentEvaluation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计算编排：按情景 + 方法 + as_of 执行评估并持久化。
 * 同一 (scenario, method, as_of) 幂等——重复提交返回既有运行（replayed=true），
 * 保证历史时点复算结果稳定；两条路线独立执行，互不作为预处理或核验。
 */
@Service
public class ComputationService {

    private final ScenarioRepo scenarioRepo;
    private final EventRepo eventRepo;
    private final SegmentRepo segmentRepo;
    private final ObservationRepo obsRepo;
    private final RunRepo runRepo;
    private final IntervalPropagationEvaluator intervalEvaluator;
    private final CalibratedEnsembleEvaluator ensembleEvaluator;
    private final ObjectMapper objectMapper;

    public ComputationService(ScenarioRepo scenarioRepo, EventRepo eventRepo, SegmentRepo segmentRepo,
                              ObservationRepo obsRepo, RunRepo runRepo,
                              IntervalPropagationEvaluator intervalEvaluator,
                              CalibratedEnsembleEvaluator ensembleEvaluator,
                              ObjectMapper objectMapper) {
        this.scenarioRepo = scenarioRepo;
        this.eventRepo = eventRepo;
        this.segmentRepo = segmentRepo;
        this.obsRepo = obsRepo;
        this.runRepo = runRepo;
        this.intervalEvaluator = intervalEvaluator;
        this.ensembleEvaluator = ensembleEvaluator;
        this.objectMapper = objectMapper;
    }

    public record RunOutcome(ComputationRun run, boolean replayed) {}

    @Transactional
    public RunOutcome run(long scenarioId, EvalMethod method, OffsetDateTime asOf) {
        Scenario scenario = scenarioRepo.findById(scenarioId)
                .orElseThrow(() -> ApiException.notFound("情景不存在: " + scenarioId));
        OffsetDateTime effectiveAsOf = (asOf != null ? asOf : OffsetDateTime.now())
                .truncatedTo(ChronoUnit.SECONDS);

        // 幂等：同 (scenario, method, as_of) 直接返回既有运行
        var existing = runRepo.findRun(scenarioId, method.name(), effectiveAsOf);
        if (existing.isPresent()) {
            return new RunOutcome(existing.get(), true);
        }

        HydroEvent event = eventRepo.findById(scenario.eventId()).orElseThrow();
        List<Segment> path = orderedPath(scenario);
        EvalContext ctx = buildContext(scenario, event, effectiveAsOf);

        List<SegmentEvaluation> evaluations = switch (method) {
            case INTERVAL_PROPAGATION ->
                    path.stream().map(seg -> intervalEvaluator.evaluateSegment(seg, ctx)).toList();
            case CALIBRATED_ENSEMBLE -> ensembleEvaluator.evaluatePath(path, ctx);
        };

        String runCode = "RUN-" + scenarioId + "-" + method.name() + "-" + effectiveAsOf.toEpochSecond();
        long runId = runRepo.insertRun(runCode, scenarioId, method.name(), effectiveAsOf);
        Map<Long, String> codeById = new LinkedHashMap<>();
        path.forEach(s -> codeById.put(s.id(), s.segmentCode()));

        try {
            for (SegmentEvaluation ev : evaluations) {
                runRepo.insertResult(runId, ev.segmentId(), ev.qMinLps(), ev.qMaxLps(),
                        ev.marginMin(), ev.marginMax(),
                        objectMapper.writeValueAsString(ev.contributions()),
                        objectMapper.writeValueAsString(ev.limitations()),
                        ev.undeterminableReason(),
                        objectMapper.writeValueAsString(ev.evidence()));
                for (SegmentEvaluation.LedgerLine line : ev.ledger()) {
                    runRepo.insertLedger(runId, ev.segmentId(), line.item(), line.status(), line.detail());
                }
            }
            runRepo.finishRun(runId, objectMapper.writeValueAsString(
                    buildNetworkSummary(evaluations, codeById)));
        } catch (Exception e) {
            throw ApiException.badRequest("结果序列化失败: " + e.getMessage());
        }
        return new RunOutcome(runRepo.findRunById(runId).orElseThrow(), false);
    }

    /** 网络情景汇总：瓶颈排序 + 网络可通过流量区间（串联路径取各段最小）。 */
    private Map<String, Object> buildNetworkSummary(List<SegmentEvaluation> evaluations,
                                                    Map<Long, String> codeById) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<SegmentEvaluation> determinable = evaluations.stream()
                .filter(e -> e.qMaxLps() != null).toList();
        List<Map<String, Object>> undeterminable = evaluations.stream()
                .filter(e -> e.qMaxLps() == null)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("segmentCode", codeById.get(e.segmentId()));
                    m.put("reason", e.undeterminableReason());
                    return m;
                }).toList();

        if (!determinable.isEmpty()) {
            summary.put("networkQMinLps", determinable.stream()
                    .mapToDouble(SegmentEvaluation::qMinLps).min().orElseThrow());
            summary.put("networkQMaxLps", determinable.stream()
                    .mapToDouble(SegmentEvaluation::qMaxLps).min().orElseThrow());
            List<Map<String, Object>> ranking = determinable.stream()
                    .sorted(Comparator.comparingDouble(SegmentEvaluation::qMaxLps))
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<String, Object>();
                        m.put("segmentCode", codeById.get(e.segmentId()));
                        m.put("qMinLps", e.qMinLps());
                        m.put("qMaxLps", e.qMaxLps());
                        m.put("marginMin", e.marginMin());
                        m.put("marginMax", e.marginMax());
                        m.put("limitations", e.limitations());
                        return m;
                    }).toList();
            summary.put("bottleneckRanking", ranking);
            summary.put("bottleneckSegment", ranking.get(0).get("segmentCode"));
        } else {
            summary.put("networkQMinLps", null);
            summary.put("networkQMaxLps", null);
            summary.put("bottleneckRanking", List.of());
            summary.put("bottleneckSegment", null);
        }
        summary.put("undeterminableSegments", undeterminable);
        summary.put("note", "网络区间为串联路径各段区间最小值；瓶颈按 qMax 升序。仅同一事件窗口内数据参与，未对齐峰值不合成。");
        return summary;
    }

    private List<Segment> orderedPath(Scenario scenario) {
        Map<Long, Segment> byId = new LinkedHashMap<>();
        segmentRepo.listByIds(scenario.pathSegmentIds()).forEach(s -> byId.put(s.id(), s));
        List<Segment> path = new ArrayList<>();
        for (Long id : scenario.pathSegmentIds()) {
            Segment s = byId.get(id);
            if (s == null) {
                throw ApiException.notFound("情景路径中的管段不存在: " + id);
            }
            path.add(s);
        }
        return path;
    }

    /** 由情景取值规则构建计算上下文：闸门钉定 + 支持观测（as_of 前入库）。 */
    private EvalContext buildContext(Scenario scenario, HydroEvent event, OffsetDateTime asOf) {
        JsonNode spec;
        try {
            spec = objectMapper.readTree(scenario.boundarySpecJson());
        } catch (Exception e) {
            throw ApiException.unprocessable("情景 boundary_spec 无法解析");
        }
        String boundaryCode = spec.path("downstreamBoundaryCode").asText();
        Map<String, EvalContext.GatePin> gatePins = new LinkedHashMap<>();
        for (JsonNode g : spec.path("gates")) {
            String gateCode = g.path("gateCode").asText();
            String segmentCode = g.path("segmentCode").asText();
            GateState pinned = GateState.parse(g.path("pinnedState").asText());
            if (pinned == null) {
                continue;
            }
            List<Long> supportIds = obsRepo.forComputation(Vocab.OBS_GATE_STATE, gateCode,
                            event.windowStart(), event.windowEnd(), asOf).stream()
                    .filter(o -> pinned.name().equalsIgnoreCase(o.valueText()))
                    .map(Observation::id)
                    .toList();
            gatePins.put(segmentCode, new EvalContext.GatePin(gateCode, pinned, supportIds,
                    !supportIds.isEmpty()));
        }
        return new EvalContext(event.windowStart(), event.windowEnd(), asOf, gatePins, boundaryCode);
    }

    public ComputationRun getRun(long runId) {
        return runRepo.findRunById(runId)
                .orElseThrow(() -> ApiException.notFound("计算运行不存在: " + runId));
    }

    public List<CapacityResult> getResults(long runId) {
        return runRepo.listResults(runId);
    }

    public List<CoverageEntry> getCoverage(long runId) {
        return runRepo.listLedger(runId);
    }

    public List<ComputationRun> listRunsByScenario(long scenarioId) {
        return runRepo.listRunsByScenario(scenarioId);
    }
}
