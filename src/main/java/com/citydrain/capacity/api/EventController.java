package com.citydrain.capacity.api;

import com.citydrain.capacity.domain.HydroEvent;
import com.citydrain.capacity.repo.EventRepo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 水文事件接口。 */
@RestController
@RequestMapping("/api/events")
@Tag(name = "水文事件")
public class EventController {

    private final EventRepo eventRepo;

    public EventController(EventRepo eventRepo) {
        this.eventRepo = eventRepo;
    }

    public record EventReq(@NotBlank String eventCode, String name,
                           @NotNull OffsetDateTime windowStart,
                           @NotNull OffsetDateTime windowEnd) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记水文事件（同一事件的观测组合边界；窗口外峰值不合成进来）")
    public Map<String, Object> create(@Valid @RequestBody EventReq req) {
        if (!req.windowStart().isBefore(req.windowEnd())) {
            throw com.citydrain.capacity.service.ApiException.badRequest("windowStart 必须早于 windowEnd");
        }
        long id = eventRepo.insert(req.eventCode(), req.name(), req.windowStart(), req.windowEnd());
        return Map.of("id", id, "eventCode", req.eventCode());
    }

    @GetMapping
    @Operation(summary = "列出水文事件")
    public List<HydroEvent> list() {
        return eventRepo.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "事件详情")
    public HydroEvent get(@PathVariable long id) {
        return eventRepo.findById(id)
                .orElseThrow(() -> com.citydrain.capacity.service.ApiException.notFound("事件不存在: " + id));
    }
}
