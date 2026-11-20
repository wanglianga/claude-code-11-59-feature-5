package com.citydrain.capacity.api;

import com.citydrain.capacity.domain.ComputationRun;
import com.citydrain.capacity.domain.EvalMethod;
import com.citydrain.capacity.domain.Scenario;
import com.citydrain.capacity.service.ComputationService;
import com.citydrain.capacity.service.ScenarioAssembler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 情景组装与网络情景查询接口。 */
@RestController
@RequestMapping("/api/scenarios")
@Tag(name = "情景组装与网络情景查询")
public class ScenarioController {

    private final ScenarioAssembler assembler;
    private final ComputationService computationService;

    public ScenarioController(ScenarioAssembler assembler, ComputationService computationService) {
        this.assembler = assembler;
        this.computationService = computationService;
    }

    public record GateSpecReq(@NotNull String gateCode, @NotNull String segmentCode) {}

    public record AssembleReq(@NotNull Long eventId,
                              @NotNull Long networkVersionId,
                              @NotEmpty List<Long> pathSegmentIds,
                              @NotNull String downstreamBoundaryCode,
                              List<GateSpecReq> gates) {}

    @PostMapping("/assemble")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "按同一水文事件+管网版本组装情景；闸门冲突拆分为条件情景（不取平均）")
    public Map<String, Object> assemble(@Valid @RequestBody AssembleReq req) {
        ScenarioAssembler.AssembleResult result = assembler.assemble(
                req.eventId(), req.networkVersionId(), req.pathSegmentIds(),
                req.downstreamBoundaryCode(),
                req.gates() == null ? List.of()
                        : req.gates().stream()
                                .map(g -> new ScenarioAssembler.GateSpec(g.gateCode(), g.segmentCode()))
                                .toList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scenarios", result.scenarios());
        body.put("conflicts", result.conflicts());
        body.put("droppedGates", result.droppedGates());
        return body;
    }

    @GetMapping
    @Operation(summary = "列出情景")
    public List<Scenario> list() {
        return assembler.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "情景详情")
    public Scenario get(@PathVariable long id) {
        return assembler.findById(id);
    }

    @GetMapping("/{id}/capacity")
    @Operation(summary = "网络情景查询：该情景各次计算运行的结果与网络汇总（可按方法过滤）")
    public Map<String, Object> capacity(@PathVariable long id,
                                        @RequestParam(required = false) EvalMethod method) {
        Scenario scenario = assembler.findById(id);
        List<ComputationRun> runs = computationService.listRunsByScenario(id).stream()
                .filter(r -> method == null || r.method().equals(method.name()))
                .toList();
        List<Map<String, Object>> runViews = runs.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("runId", r.id());
            m.put("runCode", r.runCode());
            m.put("method", r.method());
            m.put("asOf", r.asOf());
            m.put("networkSummary", json(r.networkSummaryJson()));
            m.put("results", computationService.getResults(r.id()));
            return m;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scenario", scenario);
        body.put("runs", runViews);
        return body;
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
