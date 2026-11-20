package com.citydrain.capacity.api;

import com.citydrain.capacity.domain.Observation;
import com.citydrain.capacity.service.ObservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 运行观测库接口：批量入库（ingested_at 取服务器时间）与查询。 */
@RestController
@RequestMapping("/api/observations")
@Tag(name = "运行观测库")
public class ObservationController {

    private final ObservationService obsService;

    public ObservationController(ObservationService obsService) {
        this.obsService = obsService;
    }

    public record ObsReq(@NotBlank String obsType,
                         @NotBlank String targetType,
                         @NotBlank String targetCode,
                         Double valueNum,
                         String valueText,
                         String unit,
                         @NotNull OffsetDateTime sampledAt,
                         String source,
                         String sourceVersion,
                         String calibrationStatus) {}

    public record BatchReq(@NotEmpty List<@Valid ObsReq> observations) {}

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "批量入库观测（迟到补录：sampledAt 可早于入库时间）")
    public Map<String, Object> ingest(@Valid @RequestBody BatchReq req) {
        List<Observation> obs = req.observations().stream()
                .map(o -> new Observation(0, o.obsType(), o.targetType(), o.targetCode(),
                        o.valueNum(), o.valueText(), o.unit(), o.sampledAt(), null,
                        o.source(), o.sourceVersion(),
                        o.calibrationStatus() == null ? "RAW" : o.calibrationStatus()))
                .toList();
        List<Long> ids = obsService.ingest(obs);
        return Map.of("ingested", ids.size(), "ids", ids);
    }

    @GetMapping
    @Operation(summary = "查询观测")
    public List<Observation> query(@RequestParam(required = false) String obsType,
                                   @RequestParam(required = false) String targetCode,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                   @RequestParam(defaultValue = "1000") int limit) {
        return obsService.query(obsType, targetCode, from, to, limit);
    }
}
