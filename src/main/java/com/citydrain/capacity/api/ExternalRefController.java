package com.citydrain.capacity.api;

import com.citydrain.capacity.domain.ExternalRef;
import com.citydrain.capacity.domain.ParamVersion;
import com.citydrain.capacity.service.ExternalRefService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 外部只读引用接口：缺陷观测 / 检查覆盖 / 维修记录。
 * 只保存引用及来源版本；不重新定位缺陷、不裁决观测、不推导覆盖与维修状态。
 */
@RestController
@RequestMapping("/api/external-refs")
@Tag(name = "外部只读引用")
public class ExternalRefController {

    private final ExternalRefService extService;

    public ExternalRefController(ExternalRefService extService) {
        this.extService = extService;
    }

    public record RefReq(@NotBlank String refType,
                         @NotBlank String externalId,
                         @NotBlank String sourceSystem,
                         @NotBlank String sourceVersion,
                         Long segmentId,
                         OffsetDateTime observedAt,
                         @NotNull JsonNode payload) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记外部只读引用（来源系统+来源版本+原始载荷）")
    public Object register(@Valid @RequestBody RefReq req) {
        long id = extService.register(req.refType(), req.externalId(), req.sourceSystem(),
                req.sourceVersion(), req.segmentId(), req.observedAt(), req.payload());
        return extService.findById(id).orElseThrow();
    }

    @GetMapping
    @Operation(summary = "查询外部引用（只读，不提供修改/删除）")
    public List<ExternalRef> list(@RequestParam(required = false) String refType,
                                  @RequestParam(required = false) Long segmentId) {
        return extService.list(refType, segmentId);
    }

    @PostMapping("/{id}/derive-parameter-version")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "维修验收派生新参数版本（需有验收且实测参数变化可追溯；旧版本保留）")
    public ParamVersion derive(@PathVariable long id, Authentication auth) {
        String actor = auth != null ? auth.getName() : "api";
        return extService.deriveParameterVersionFromRepair(id, actor);
    }
}
