package com.citydrain.capacity.api;

import com.citydrain.capacity.domain.ConditionAssessmentVersion;
import com.citydrain.capacity.domain.ConditionPositionEntry;
import com.citydrain.capacity.domain.ReinspectionCandidate;
import com.citydrain.capacity.domain.VoiEstimation;
import com.citydrain.capacity.domain.VoiEstimationResult;
import com.citydrain.capacity.service.voi.VoiService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 复检信息价值（VOI）接口：候选方案、外部状况评估版本（只读引用快照）、VOI 估计。
 * 估计结果与评估版本不可变（无修改/删除接口）；外部系统仍为缺陷/覆盖/维修数据的唯一裁决方。
 */
@RestController
@RequestMapping("/api/reinspection")
@Tag(name = "复检信息价值 VOI")
public class VoiController {

    private final VoiService voiService;

    public VoiController(VoiService voiService) {
        this.voiService = voiService;
    }

    // ---------------- 候选方案 ----------------

    public record CandidateReq(
            @NotBlank String candidateCode,
            @Positive long segmentId,
            @NotBlank String methodCode,
            @Positive double methodResolutionMm,
            @PositiveOrZero double locationErrorM,
            @PositiveOrZero double cost,
            @NotNull JsonNode detectableCategories,
            @NotNull JsonNode locationRange,
            boolean supplierReachable,
            boolean rainySeasonBlocked,
            String note) {}

    @PostMapping("/candidates")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记复检候选方案（覆盖位置/分辨率/定位误差/成本/可发现缺陷类别）")
    public ReinspectionCandidate registerCandidate(@Valid @RequestBody CandidateReq req) {
        long id = voiService.registerCandidate(req.candidateCode(), req.segmentId(), req.methodCode(),
                req.methodResolutionMm(), req.locationErrorM(), req.cost(),
                req.detectableCategories(), req.locationRange(),
                req.supplierReachable(), req.rainySeasonBlocked(), req.note());
        return voiService.getCandidate(id);
    }

    @GetMapping("/candidates")
    @Operation(summary = "查询全部复检候选")
    public List<ReinspectionCandidate> listCandidates() {
        return voiService.listCandidates();
    }

    @GetMapping("/candidates/{id}")
    @Operation(summary = "查询单个复检候选")
    public ReinspectionCandidate getCandidate(@PathVariable long id) {
        return voiService.getCandidate(id);
    }

    // ---------------- 状况评估版本（外部只读引用快照） ----------------

    public record AssessmentReq(
            @NotBlank String assessmentCode,
            @NotBlank String sourceSystem,
            @NotBlank String sourceVersion,
            @NotNull List<@Positive Long> externalRefIds,
            @Positive long networkVersionId,
            @NotNull JsonNode decisionThresholds,
            @NotNull List<@NotNull JsonNode> entries) {}

    @PostMapping("/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记外部状况评估版本（只读引用+风险区间/证据冲突/决策阈值快照，不可变）")
    public ConditionAssessmentVersion registerAssessment(@Valid @RequestBody AssessmentReq req) {
        long id = voiService.registerAssessment(req.assessmentCode(), req.sourceSystem(),
                req.sourceVersion(), req.externalRefIds(), req.networkVersionId(),
                req.decisionThresholds(), req.entries());
        return voiService.getAssessment(id);
    }

    @GetMapping("/assessments")
    @Operation(summary = "查询状况评估版本列表")
    public List<ConditionAssessmentVersion> listAssessments() {
        return voiService.listAssessments();
    }

    @GetMapping("/assessments/{id}")
    @Operation(summary = "查询单个状况评估版本")
    public ConditionAssessmentVersion getAssessment(@PathVariable long id) {
        return voiService.getAssessment(id);
    }

    @GetMapping("/assessments/{id}/entries")
    @Operation(summary = "查询评估版本内各位置风险区间/证据冲突/当前决定")
    public List<ConditionPositionEntry> listEntries(@PathVariable long id) {
        return voiService.listAssessmentEntries(id);
    }

    // ---------------- VOI 估计 ----------------

    public record EstimateReq(
            @Positive long assessmentId,
            String method,                       // ENUMERATION / SENSITIVITY / AUTO（默认 AUTO）
            List<@Positive Long> candidateIds,   // 空 = 全部 ACTIVE 候选
            OffsetDateTime asOf) {}

    @PostMapping("/estimations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "执行复检信息价值估计（两路线同一 evi 口径；结果不可变、同请求可复算）")
    public Map<String, Object> estimate(@Valid @RequestBody EstimateReq req) {
        VoiService.EstimateOutcome outcome = voiService.estimate(req.assessmentId(),
                req.method() == null || req.method().isBlank() ? "AUTO" : req.method(),
                req.candidateIds(), req.asOf());
        return Map.of(
                "estimation", outcome.estimation(),
                "results", outcome.results(),
                "selectedMethod", outcome.selectedMethod(),
                "selectionReason", outcome.selectionReason());
    }

    @GetMapping("/estimations/{id}")
    @Operation(summary = "查询 VOI 估计批次（含方法选择依据快照）")
    public VoiEstimation getEstimation(@PathVariable long id) {
        return voiService.getEstimation(id);
    }

    @GetMapping("/estimations/{id}/results")
    @Operation(summary = "查询每候选 EVI/决策变化概率/可缩小范围/低价值标记（按 evi 排名）")
    public List<VoiEstimationResult> getResults(@PathVariable long id) {
        return voiService.getResults(id);
    }

    @GetMapping("/assessments/{id}/estimations")
    @Operation(summary = "查询某状况评估版本下的全部估计批次")
    public List<VoiEstimation> listByAssessment(@PathVariable long id) {
        return voiService.listByAssessment(id);
    }
}
