package com.citydrain.capacity.api;

import com.citydrain.capacity.domain.ParamVersion;
import com.citydrain.capacity.domain.Segment;
import com.citydrain.capacity.repo.NetworkVersionRepo;
import com.citydrain.capacity.repo.SegmentRepo;
import com.citydrain.capacity.service.ApiException;
import com.citydrain.capacity.service.ParameterVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 资产参数库接口：管网版本、管段、经确认的参数版本。 */
@RestController
@RequestMapping("/api")
@Tag(name = "资产参数库")
public class AssetController {

    private final NetworkVersionRepo nvRepo;
    private final SegmentRepo segmentRepo;
    private final ParameterVersionService paramService;

    public AssetController(NetworkVersionRepo nvRepo, SegmentRepo segmentRepo,
                           ParameterVersionService paramService) {
        this.nvRepo = nvRepo;
        this.segmentRepo = segmentRepo;
        this.paramService = paramService;
    }

    public record NetworkVersionReq(@NotBlank String code, String description) {}

    public record SegmentReq(@NotBlank String segmentCode,
                             @NotBlank String fromNode,
                             @NotBlank String toNode,
                             @Positive double lengthM,
                             @Positive double nominalDiameterMm,
                             double upstreamInvertM,
                             double downstreamInvertM,
                             @Positive double designFlowLps) {}

    public record ParamVersionReq(@NotBlank String source,
                                  Double diameterMinMm, Double diameterMaxMm,
                                  Double slopeMin, Double slopeMax,
                                  Double roughnessMin, Double roughnessMax,
                                  Double siltRatioMin, Double siltRatioMax,
                                  @NotNull OffsetDateTime validFrom,
                                  String note) {}

    @PostMapping("/network-versions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记管网版本")
    public Map<String, Object> createNetworkVersion(@Valid @RequestBody NetworkVersionReq req) {
        if (nvRepo.findIdByCode(req.code()).isPresent()) {
            throw ApiException.conflict("管网版本已存在: " + req.code());
        }
        long id = nvRepo.insert(req.code(), req.description());
        return Map.of("id", id, "code", req.code());
    }

    @GetMapping("/network-versions")
    @Operation(summary = "列出管网版本")
    public List<String> listNetworkVersions() {
        return nvRepo.listCodes();
    }

    @PostMapping("/network-versions/{code}/segments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记管段（名义属性与设计需求）")
    public Map<String, Object> createSegment(@PathVariable String code,
                                             @Valid @RequestBody SegmentReq req) {
        long nvId = nvRepo.findIdByCode(code)
                .orElseThrow(() -> ApiException.notFound("管网版本不存在: " + code));
        if (segmentRepo.findByCode(nvId, req.segmentCode()).isPresent()) {
            throw ApiException.conflict("管段已存在: " + req.segmentCode());
        }
        long id = segmentRepo.insert(nvId, req.segmentCode(), req.fromNode(), req.toNode(),
                req.lengthM(), req.nominalDiameterMm(), req.upstreamInvertM(),
                req.downstreamInvertM(), req.designFlowLps());
        return Map.of("id", id, "segmentCode", req.segmentCode());
    }

    @GetMapping("/network-versions/{code}/segments")
    @Operation(summary = "列出管网版本下的管段")
    public List<Segment> listSegments(@PathVariable String code) {
        long nvId = nvRepo.findIdByCode(code)
                .orElseThrow(() -> ApiException.notFound("管网版本不存在: " + code));
        return segmentRepo.listByNetworkVersion(nvId);
    }

    @PostMapping("/segments/{id}/parameter-versions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记经确认的参数版本（自动关闭上一打开版本，旧版本保留）")
    public ParamVersion createParamVersion(@PathVariable long id,
                                           @Valid @RequestBody ParamVersionReq req,
                                           @RequestParam(defaultValue = "api") String actor) {
        return paramService.createVersion(id, req.source(),
                req.diameterMinMm(), req.diameterMaxMm(), req.slopeMin(), req.slopeMax(),
                req.roughnessMin(), req.roughnessMax(), req.siltRatioMin(), req.siltRatioMax(),
                req.validFrom(), actor, req.note());
    }

    @GetMapping("/segments/{id}/parameter-versions")
    @Operation(summary = "列出管段参数版本；at 参数可解析历史时点有效版本（不回填最新值）")
    public Object listParamVersions(@PathVariable long id,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                    OffsetDateTime at) {
        if (at != null) {
            return paramService.resolveAt(id, at)
                    .<Object>map(v -> v)
                    .orElse(Map.of("resolved", null, "note",
                            "该时点无有效参数版本（不以最新值回填历史）"));
        }
        return paramService.listBySegment(id);
    }
}
