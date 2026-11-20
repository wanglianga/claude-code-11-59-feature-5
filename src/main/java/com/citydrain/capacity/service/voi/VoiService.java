package com.citydrain.capacity.service.voi;

import com.citydrain.capacity.domain.ConditionAssessmentVersion;
import com.citydrain.capacity.domain.ConditionPositionEntry;
import com.citydrain.capacity.domain.ExternalRef;
import com.citydrain.capacity.domain.ReinspectionCandidate;
import com.citydrain.capacity.domain.Segment;
import com.citydrain.capacity.domain.VoiEstimation;
import com.citydrain.capacity.domain.VoiEstimationResult;
import com.citydrain.capacity.domain.Vocab;
import com.citydrain.capacity.repo.ConditionAssessmentRepo;
import com.citydrain.capacity.repo.ExternalRefRepo;
import com.citydrain.capacity.repo.NetworkVersionRepo;
import com.citydrain.capacity.repo.ReinspectionCandidateRepo;
import com.citydrain.capacity.repo.SegmentRepo;
import com.citydrain.capacity.repo.VoiEstimationRepo;
import com.citydrain.capacity.service.ApiException;
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
 * 复检信息价值估计编排：
 * 登记候选与外部状况评估版本（只读引用快照）→ 选择估计路线 → 逐候选评分 → 不可变持久化。
 * 路线选择依据候选规模、结果分布离散性与组合优化需求；两条路线独立产出同一口径 evi，
 * 敏感度法不作为枚举法的预处理或预热。
 */
@Service
public class VoiService {

    private final ReinspectionCandidateRepo candidateRepo;
    private final ConditionAssessmentRepo assessmentRepo;
    private final ExternalRefRepo extRepo;
    private final SegmentRepo segmentRepo;
    private final NetworkVersionRepo networkVersionRepo;
    private final VoiEstimationRepo estimationRepo;
    private final EnumerationVoiEvaluator enumerationEvaluator;
    private final SensitivityVoiEvaluator sensitivityEvaluator;
    private final RepairCoverageResolver repairResolver;
    private final VoiScoringSupport support;
    private final ObjectMapper objectMapper;

    public VoiService(ReinspectionCandidateRepo candidateRepo, ConditionAssessmentRepo assessmentRepo,
                      ExternalRefRepo extRepo, SegmentRepo segmentRepo, NetworkVersionRepo networkVersionRepo,
                      VoiEstimationRepo estimationRepo,
                      EnumerationVoiEvaluator enumerationEvaluator,
                      SensitivityVoiEvaluator sensitivityEvaluator,
                      RepairCoverageResolver repairResolver, VoiScoringSupport support,
                      ObjectMapper objectMapper) {
        this.candidateRepo = candidateRepo;
        this.assessmentRepo = assessmentRepo;
        this.extRepo = extRepo;
        this.segmentRepo = segmentRepo;
        this.networkVersionRepo = networkVersionRepo;
        this.estimationRepo = estimationRepo;
        this.enumerationEvaluator = enumerationEvaluator;
        this.sensitivityEvaluator = sensitivityEvaluator;
        this.repairResolver = repairResolver;
        this.support = support;
        this.objectMapper = objectMapper;
    }

    // ---------------- 登记 ----------------

    @Transactional
    public long registerCandidate(String candidateCode, long segmentId, String methodCode,
                                  double resolutionMm, double locationErrorM, double cost,
                                  JsonNode detectableCategories, JsonNode locationRange,
                                  boolean supplierReachable, boolean rainyBlocked, String note) {
        segmentRepo.findById(segmentId)
                .orElseThrow(() -> ApiException.notFound("管段不存在: " + segmentId));
        if (resolutionMm <= 0 || locationErrorM < 0 || cost < 0) {
            throw ApiException.badRequest("分辨率须为正、定位误差与成本不可为负");
        }
        if (candidateRepo.findByCode(candidateCode).isPresent()) {
            throw ApiException.conflict("候选方案编号已存在: " + candidateCode);
        }
        ReinspectionCandidate c = new ReinspectionCandidate(0, candidateCode, segmentId, methodCode,
                resolutionMm, locationErrorM, cost,
                support.toJson(detectableCategories), support.toJson(locationRange),
                supplierReachable, rainyBlocked, Vocab.CAND_ACTIVE, note, null);
        return candidateRepo.insert(c);
    }

    @Transactional
    public long registerAssessment(String assessmentCode, String sourceSystem, String sourceVersion,
                                   List<Long> externalRefIds, long networkVersionId,
                                   JsonNode thresholds, List<JsonNode> entries) {
        if (!networkVersionRepo.existsById(networkVersionId)) {
            throw ApiException.notFound("管网版本不存在: " + networkVersionId);
        }
        for (Long refId : externalRefIds) {
            extRepo.findById(refId)
                    .orElseThrow(() -> ApiException.unprocessable("外部只读引用不存在: " + refId));
        }
        if (assessmentRepo.listVersions().stream()
                .anyMatch(v -> v.sourceSystem().equals(sourceSystem)
                        && v.sourceVersion().equals(sourceVersion))) {
            throw ApiException.conflict("相同来源系统+版本的状况评估已登记: "
                    + sourceSystem + "@" + sourceVersion);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entries", entries);
        long id = assessmentRepo.insertVersion(new ConditionAssessmentVersion(0, assessmentCode,
                sourceSystem, sourceVersion, support.toJson(externalRefIds), networkVersionId,
                support.toJson(thresholds), support.toJson(payload), null));
        for (JsonNode e : entries) {
            long segmentId = e.path("segmentId").asLong();
            segmentRepo.findById(segmentId)
                    .orElseThrow(() -> ApiException.unprocessable("位置条目管段不存在: " + segmentId));
            assessmentRepo.insertEntry(new ConditionPositionEntry(0, id, segmentId,
                    support.toJson(e.path("locationRange")),
                    e.path("riskScoreMin").asDouble(), e.path("riskScoreMax").asDouble(),
                    e.path("evidenceConflict").asBoolean(false),
                    e.path("locationAmbiguityM").asDouble(0),
                    e.path("currentDecision").asText(Vocab.DEC_MONITOR),
                    e.path("currentRiskLengthM").asDouble(0),
                    e.path("note").isTextual() ? e.path("note").asText()
                            : (e.has("note") ? support.toJson(e.path("note")) : null)));
        }
        return id;
    }

    // ---------------- 估计 ----------------

    public record EstimateOutcome(VoiEstimation estimation, List<VoiEstimationResult> results,
                                  String selectedMethod, String selectionReason) {}

    /**
     * 执行 VOI 估计。method 取 ENUMERATION / SENSITIVITY / AUTO。
     * AUTO：候选规模小且结果分布离散（证据冲突/待验收维修/近阈值）→ 枚举法；
     * 候选规模大 → 敏感度法。显式指定的路线总是照用，两路线互不预处理。
     */
    @Transactional
    public EstimateOutcome estimate(long assessmentId, String requestedMethod,
                                    List<Long> candidateIds, OffsetDateTime asOf) {
        ConditionAssessmentVersion assessment = assessmentRepo.findVersionById(assessmentId)
                .orElseThrow(() -> ApiException.notFound("状况评估版本不存在: " + assessmentId));
        OffsetDateTime effectiveAsOf = (asOf != null ? asOf : OffsetDateTime.now())
                .truncatedTo(ChronoUnit.SECONDS);

        List<ReinspectionCandidate> candidates = (candidateIds == null || candidateIds.isEmpty())
                ? candidateRepo.listAll().stream().filter(c -> Vocab.CAND_ACTIVE.equals(c.status())).toList()
                : candidateRepo.listByIds(candidateIds);
        if (candidates.isEmpty()) {
            throw ApiException.unprocessable("没有可估计的复检候选（ACTIVE）");
        }
        List<ConditionPositionEntry> entries = assessmentRepo.listEntries(assessmentId);
        Map<Long, ConditionPositionEntry> entryBySegment = new LinkedHashMap<>();
        entries.forEach(e -> entryBySegment.put(e.segmentId(), e));

        // 当前风险排序（按风险区间上界降序），用于网络影响结论分量
        List<ConditionPositionEntry> ranked = entries.stream()
                .sorted(Comparator.comparingDouble(ConditionPositionEntry::riskScoreMax).reversed())
                .toList();
        Map<Long, Integer> rankBySegment = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            rankBySegment.put(ranked.get(i).segmentId(), i + 1);
        }
        String bottleneckCode = ranked.isEmpty() ? null
                : segmentRepo.findById(ranked.get(0).segmentId()).map(Segment::segmentCode).orElse(null);

        VoiInputs.ThresholdContext thresholds = parseThresholds(assessment.decisionThresholdsJson());
        boolean discrete = candidates.stream().anyMatch(c -> {
            ConditionPositionEntry e = entryBySegment.get(c.segmentId());
            if (e == null) return false;
            return e.evidenceConflict() || hasPendingRepair(c) || nearThreshold(e, thresholds);
        });

        String method = selectMethod(requestedMethod, candidates.size(), discrete);
        String reason = selectionReason(requestedMethod, method, candidates.size(), discrete);

        // 幂等：同 (评估版本, 路线, as_of) 直接重放既有批次（结果不可变，按相同输入可复算）
        var existing = estimationRepo.findEstimation(assessmentId, method, effectiveAsOf);
        if (existing.isPresent()) {
            VoiEstimation est = existing.get();
            return new EstimateOutcome(est, estimationRepo.listResults(est.id()), method,
                    "重放既有估计批次（同 assessment+method+as_of 幂等，结果不可变）");
        }

        List<VoiInputs.CandidateContext> contexts = new ArrayList<>();
        for (ReinspectionCandidate c : candidates) {
            ConditionPositionEntry entry = entryBySegment.get(c.segmentId());
            double[] range = parseRange(c.locationRangeJson());
            List<ExternalRef> repairs = extRepo.listBySegmentAndType(c.segmentId(), Vocab.REF_REPAIR);
            RepairCoverageResolver.Coverage coverage = repairResolver.resolve(
                    repairs, range[0], range[1]);
            contexts.add(new VoiInputs.CandidateContext(c, entry,
                    coverage.repairedAcceptedCovered(), coverage.pendingRepair(), coverage.detail(),
                    rankBySegment.getOrDefault(c.segmentId(), 0), entries.size(), bottleneckCode));
        }

        List<VoiInputs.CandidateScore> scores = contexts.stream()
                .map(ctx -> Vocab.VOI_ENUMERATION.equals(method)
                        ? enumerationEvaluator.evaluate(ctx, thresholds)
                        : sensitivityEvaluator.evaluate(ctx, thresholds))
                .sorted(Comparator.comparingDouble(VoiInputs.CandidateScore::evi).reversed()
                        .thenComparing(VoiInputs.CandidateScore::candidateId))
                .toList();

        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("assessmentId", assessmentId);
        basis.put("assessmentCode", assessment.assessmentCode());
        basis.put("sourceVersion", assessment.sourceVersion());
        basis.put("requestedMethod", requestedMethod);
        basis.put("selectedMethod", method);
        basis.put("selectionReason", reason);
        basis.put("candidateCount", candidates.size());
        basis.put("discreteOutcomeDistribution", discrete);
        basis.put("thresholds", thresholds);
        basis.put("asOf", effectiveAsOf.toString());

        String code = "VOI-" + assessmentId + "-" + method + "-" + effectiveAsOf.toEpochSecond();
        long estimationId = estimationRepo.insertEstimation(code, assessmentId, method, effectiveAsOf,
                candidates.size(), support.toJson(basis));

        List<VoiEstimationResult> persisted = new ArrayList<>();
        int rank = 1;
        for (VoiInputs.CandidateScore s : scores) {
            VoiEstimationResult r = new VoiEstimationResult(0, estimationId, s.candidateId(),
                    s.segmentId(), s.evi(), rank++, s.decisionChangeProb(), s.lowValue(),
                    support.toJson(s.lowValueReasons()), s.resolutionFactor(), s.excludedRepaired(),
                    s.outcomeBreakdownJson(), s.sensitivityDetailJson(), s.expectedRiskReductionM(),
                    support.toJson(Map.of("minM", s.riskLengthNarrowingM()[0],
                            "maxM", s.riskLengthNarrowingM()[1])),
                    support.toJson(s.network()));
            estimationRepo.insertResult(r);
            persisted.add(r);
        }
        VoiEstimation est = estimationRepo.findEstimationById(estimationId).orElseThrow();
        return new EstimateOutcome(est, persisted, method, reason);
    }

    private String selectMethod(String requested, int n, boolean discrete) {
        String r = requested == null || requested.isBlank() ? "AUTO" : requested.toUpperCase();
        return switch (r) {
            case Vocab.VOI_ENUMERATION, Vocab.VOI_SENSITIVITY -> r;
            default -> n <= support.props().getAutoEnumerationMaxCandidates()
                    ? Vocab.VOI_ENUMERATION : Vocab.VOI_SENSITIVITY;
        };
    }

    private String selectionReason(String requested, String selected, int n, boolean discrete) {
        String r = requested == null || requested.isBlank() ? "AUTO" : requested.toUpperCase();
        if (!"AUTO".equals(r)) {
            return "显式指定 " + r + "：按请求路线独立估计，两路线互不预处理或预热";
        }
        if (Vocab.VOI_ENUMERATION.equals(selected)) {
            return "AUTO→ENUMERATION：候选 " + n + " 个（≤ "
                    + support.props().getAutoEnumerationMaxCandidates()
                    + "），" + (discrete ? "存在证据冲突/待验收维修/近阈值的离散结果分布，枚举可捕捉阈值跳变"
                    : "规模小，枚举法可逐项复核且计算量可控");
        }
        return "AUTO→SENSITIVITY：候选 " + n + " 个（> "
                + support.props().getAutoEnumerationMaxCandidates()
                + "），枚举结果空间组合增长过快；局部敏感度法以常数次计算近似筛选，"
                + "detail 标注 mayMissDiscreteJumps，离散场景可再单独跑 ENUMERATION 对照";
    }

    private boolean hasPendingRepair(ReinspectionCandidate c) {
        double[] range = parseRange(c.locationRangeJson());
        return repairResolver.resolve(
                extRepo.listBySegmentAndType(c.segmentId(), Vocab.REF_REPAIR), range[0], range[1])
                .pendingRepair();
    }

    private boolean nearThreshold(ConditionPositionEntry e, VoiInputs.ThresholdContext th) {
        double mid = (e.riskScoreMin() + e.riskScoreMax()) / 2.0;
        return Math.abs(mid - th.monitorMin()) <= 8.0 || Math.abs(mid - th.remediateMin()) <= 8.0;
    }

    private VoiInputs.ThresholdContext parseThresholds(String json) {
        try {
            JsonNode t = objectMapper.readTree(json);
            return new VoiInputs.ThresholdContext(
                    t.path("monitorMin").asDouble(40),
                    t.path("remediateMin").asDouble(70),
                    t.path("remediationMargin").asDouble(0.1),
                    t.path("remainingRiskLps").asDouble(200));
        } catch (Exception e) {
            return new VoiInputs.ThresholdContext(40, 70, 0.1, 200);
        }
    }

    private double[] parseRange(String locationRangeJson) {
        try {
            JsonNode r = objectMapper.readTree(locationRangeJson);
            return new double[]{r.path("startM").asDouble(0), r.path("endM").asDouble(0)};
        } catch (Exception e) {
            return new double[]{0, 0};
        }
    }

    public VoiEstimation getEstimation(long id) {
        return estimationRepo.findEstimationById(id)
                .orElseThrow(() -> ApiException.notFound("VOI 估计批次不存在: " + id));
    }

    public List<VoiEstimationResult> getResults(long estimationId) {
        getEstimation(estimationId);
        return estimationRepo.listResults(estimationId);
    }

    public List<VoiEstimation> listByAssessment(long assessmentId) {
        return estimationRepo.listEstimationsByAssessment(assessmentId);
    }

    public ReinspectionCandidate getCandidate(long id) {
        return candidateRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("复检候选不存在: " + id));
    }

    public List<ReinspectionCandidate> listCandidates() {
        return candidateRepo.listAll();
    }

    public ConditionAssessmentVersion getAssessment(long id) {
        return assessmentRepo.findVersionById(id)
                .orElseThrow(() -> ApiException.notFound("状况评估版本不存在: " + id));
    }

    public List<ConditionAssessmentVersion> listAssessments() {
        return assessmentRepo.listVersions();
    }

    public List<ConditionPositionEntry> listAssessmentEntries(long assessmentId) {
        getAssessment(assessmentId);
        return assessmentRepo.listEntries(assessmentId);
    }
}
