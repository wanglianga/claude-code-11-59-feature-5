package com.citydrain.capacity.api;

import com.citydrain.capacity.domain.CapacityResult;
import com.citydrain.capacity.domain.ComputationRun;
import com.citydrain.capacity.domain.CoverageEntry;
import com.citydrain.capacity.domain.EvalMethod;
import com.citydrain.capacity.service.ComputationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 能力评估计算接口：执行评估、历史时点复算、覆盖账本与证据回查。 */
@RestController
@RequestMapping("/api/computations")
@Tag(name = "能力评估计算")
public class ComputationController {

    private final ComputationService computationService;

    public ComputationController(ComputationService computationService) {
        this.computationService = computationService;
    }

    public record ComputeReq(@NotNull Long scenarioId,
                             @NotNull EvalMethod method,
                             OffsetDateTime asOf) {}

    @PostMapping
    @Operation(summary = "执行能力评估（同 scenario+method+asOf 幂等；asOf 缺省为当前时刻）")
    public Map<String, Object> compute(@Valid @RequestBody ComputeReq req) {
        ComputationService.RunOutcome outcome = computationService.run(
                req.scenarioId(), req.method(), req.asOf());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", outcome.run().id());
        body.put("runCode", outcome.run().runCode());
        body.put("replayed", outcome.replayed());
        body.put("method", outcome.run().method());
        body.put("asOf", outcome.run().asOf());
        body.put("networkSummary", json(outcome.run().networkSummaryJson()));
        body.put("results", computationService.getResults(outcome.run().id()));
        return body;
    }

    @GetMapping("/{runId}")
    @Operation(summary = "计算运行详情（含各段能力区间结果）")
    public Map<String, Object> getRun(@PathVariable long runId) {
        ComputationRun run = computationService.getRun(runId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run", run);
        body.put("networkSummary", json(run.networkSummaryJson()));
        body.put("results", computationService.getResults(runId));
        return body;
    }

    @GetMapping("/{runId}/coverage")
    @Operation(summary = "覆盖账本：本次计算中参数和观测的可用性")
    public List<CoverageEntry> coverage(@PathVariable long runId) {
        computationService.getRun(runId);
        return computationService.getCoverage(runId);
    }

    @GetMapping("/{runId}/evidence")
    @Operation(summary = "证据回查：各段结果使用的参数版本、观测、假设与外部引用")
    public Object evidence(@PathVariable long runId, @RequestParam(required = false) Long segmentId) {
        computationService.getRun(runId);
        List<CapacityResult> results = computationService.getResults(runId).stream()
                .filter(r -> segmentId == null || r.segmentId() == segmentId)
                .toList();
        return results.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("resultId", r.id());
            m.put("segmentId", r.segmentId());
            m.put("qMinLps", r.qMinLps());
            m.put("qMaxLps", r.qMaxLps());
            m.put("limitations", json(r.limitationsJson()));
            m.put("dominantUncertainty", json(r.dominantUncertaintyJson()));
            m.put("undeterminableReason", r.undeterminableReason());
            m.put("evidence", json(r.evidenceJson()));
            return m;
        }).toList();
    }

    private Object json(String text) {
        if (text == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(text, Object.class);
        } catch (Exception e) {
            return text;
        }
    }
}
