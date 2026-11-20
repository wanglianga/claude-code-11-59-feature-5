package com.citydrain.capacity.api;

import com.citydrain.capacity.domain.ReinspectionPlan;
import com.citydrain.capacity.domain.ReinspectionPlanItem;
import com.citydrain.capacity.service.voi.ReinspectionPlanService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 复检计划版本接口：预算/供应商可达性/雨季禁入约束下的组合选择。
 * 约束变化 → 同 planCode 下生成新版本，旧计划与约束快照保留（无修改/删除接口）。
 */
@RestController
@RequestMapping("/api/reinspection/plans")
@Tag(name = "复检计划版本")
public class ReinspectionPlanController {

    private final ReinspectionPlanService planService;

    public ReinspectionPlanController(ReinspectionPlanService planService) {
        this.planService = planService;
    }

    /** 雨季窗口 rainySeasonWindow：{"start":"06-01","end":"09-30"}，可省略。 */
    public record PlanReq(
            @NotBlank String planCode,
            @Positive long estimationId,
            @PositiveOrZero double budget,
            boolean supplierOnlyReachable,
            JsonNode rainySeasonWindow) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "生成复检计划版本（0/1 背包组合优化；约束变化生成新版本，旧版本保留）")
    public Map<String, Object> generate(@Valid @RequestBody PlanReq req) {
        String window = req.rainySeasonWindow() == null || req.rainySeasonWindow().isNull()
                ? null : req.rainySeasonWindow().toString();
        ReinspectionPlanService.PlanOutcome outcome = planService.generate(req.planCode(),
                req.estimationId(), req.budget(), req.supplierOnlyReachable(), window);
        return Map.of("plan", outcome.plan(), "items", outcome.items());
    }

    @GetMapping
    @Operation(summary = "查询全部计划（含历史版本）")
    public List<ReinspectionPlan> listAll() {
        return planService.listAllPlans();
    }

    @GetMapping("/by-code/{planCode}")
    @Operation(summary = "按计划编号查询全部版本（旧计划保留当时依据）")
    public List<ReinspectionPlan> listVersions(@PathVariable String planCode) {
        return planService.listVersions(planCode);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询单个计划版本")
    public ReinspectionPlan getPlan(@PathVariable long id) {
        return planService.getPlan(id);
    }

    @GetMapping("/{id}/items")
    @Operation(summary = "查询计划明细（入选/落选及原因：预算竞争/不可达/雨季/低价值/已维修覆盖）")
    public List<ReinspectionPlanItem> getItems(@PathVariable long id) {
        return planService.getItems(id);
    }
}
