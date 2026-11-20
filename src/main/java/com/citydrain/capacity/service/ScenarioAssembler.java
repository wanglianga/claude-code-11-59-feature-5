package com.citydrain.capacity.service;

import com.citydrain.capacity.domain.GateState;
import com.citydrain.capacity.domain.HydroEvent;
import com.citydrain.capacity.domain.Observation;
import com.citydrain.capacity.domain.Scenario;
import com.citydrain.capacity.domain.Segment;
import com.citydrain.capacity.domain.Vocab;
import com.citydrain.capacity.repo.EventRepo;
import com.citydrain.capacity.repo.NetworkVersionRepo;
import com.citydrain.capacity.repo.ObservationRepo;
import com.citydrain.capacity.repo.ScenarioRepo;
import com.citydrain.capacity.repo.SegmentRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景组装器：按同一水文事件和管网版本组合边界。
 * - 闸门状态冲突 → 拆分为多个条件情景分别计算，不取平均；
 * - 时间未对齐的峰值不合成为同一工况（仅在 peakNote 中透明列出各自峰值时刻）；
 * - boundary_spec 保存取值规则，具体数值在计算时按 as_of 解析。
 */
@Service
public class ScenarioAssembler {

    private final EventRepo eventRepo;
    private final NetworkVersionRepo nvRepo;
    private final SegmentRepo segmentRepo;
    private final ObservationRepo obsRepo;
    private final ScenarioRepo scenarioRepo;
    private final ObjectMapper objectMapper;

    public ScenarioAssembler(EventRepo eventRepo, NetworkVersionRepo nvRepo, SegmentRepo segmentRepo,
                             ObservationRepo obsRepo, ScenarioRepo scenarioRepo,
                             ObjectMapper objectMapper) {
        this.eventRepo = eventRepo;
        this.nvRepo = nvRepo;
        this.segmentRepo = segmentRepo;
        this.obsRepo = obsRepo;
        this.scenarioRepo = scenarioRepo;
        this.objectMapper = objectMapper;
    }

    public record GateSpec(String gateCode, String segmentCode) {}

    public record AssembleResult(List<Scenario> scenarios, List<String> conflicts,
                                 List<String> droppedGates) {}

    @Transactional
    public AssembleResult assemble(long eventId, long networkVersionId, List<Long> pathSegmentIds,
                                   String downstreamBoundaryCode, List<GateSpec> gates) {
        HydroEvent event = eventRepo.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("水文事件不存在: " + eventId));
        if (!nvRepo.existsById(networkVersionId)) {
            throw ApiException.notFound("管网版本不存在: " + networkVersionId);
        }
        if (pathSegmentIds == null || pathSegmentIds.isEmpty()) {
            throw ApiException.badRequest("路径管段列表不能为空");
        }

        // 路径校验：存在、属于该管网版本、上下游衔接
        Map<Long, Segment> byId = new LinkedHashMap<>();
        for (Segment s : segmentRepo.listByIds(pathSegmentIds)) {
            byId.put(s.id(), s);
        }
        List<Segment> path = new ArrayList<>();
        for (Long id : pathSegmentIds) {
            Segment s = byId.get(id);
            if (s == null) {
                throw ApiException.notFound("管段不存在: " + id);
            }
            if (s.networkVersionId() != networkVersionId) {
                throw ApiException.unprocessable("管段 " + s.segmentCode() + " 不属于管网版本 " + networkVersionId);
            }
            path.add(s);
        }
        for (int i = 0; i + 1 < path.size(); i++) {
            if (!path.get(i).toNode().equals(path.get(i + 1).fromNode())) {
                throw ApiException.unprocessable("路径不衔接: " + path.get(i).segmentCode()
                        + " 下游节点 " + path.get(i).toNode() + " ≠ " + path.get(i + 1).segmentCode()
                        + " 上游节点 " + path.get(i + 1).fromNode());
            }
        }

        // 闸门状态：冲突 → 条件组合（不取平均）
        List<String> conflicts = new ArrayList<>();
        List<String> droppedGates = new ArrayList<>();
        record GateObs(GateSpec spec, List<GateState> states) {}
        List<GateObs> gateObsList = new ArrayList<>();
        Set<String> pathCodes = new LinkedHashSet<>();
        path.forEach(s -> pathCodes.add(s.segmentCode()));
        for (GateSpec g : (gates == null ? List.<GateSpec>of() : gates)) {
            if (!pathCodes.contains(g.segmentCode())) {
                throw ApiException.unprocessable("闸门 " + g.gateCode() + " 关联管段 "
                        + g.segmentCode() + " 不在路径上");
            }
            List<Observation> obs = obsRepo.query(Vocab.OBS_GATE_STATE, g.gateCode(),
                    event.windowStart(), event.windowEnd(), 1000);
            List<GateState> states = obs.stream()
                    .map(o -> GateState.parse(o.valueText()))
                    .filter(s -> s != null)
                    .distinct()
                    .toList();
            if (states.isEmpty()) {
                droppedGates.add(g.gateCode() + "（事件窗口内无状态观测，未纳入情景）");
                continue;
            }
            if (states.size() > 1) {
                conflicts.add("闸门 " + g.gateCode() + " 在事件窗口内存在冲突状态 " + states
                        + "，拆分条件情景分别计算（不取平均）");
            }
            gateObsList.add(new GateObs(g, states));
        }

        // 条件组合（笛卡尔积；单状态闸门只产生一种钉定）
        List<Map<String, GateState>> combinations = new ArrayList<>();
        combinations.add(new LinkedHashMap<>());
        for (GateObs go : gateObsList) {
            List<Map<String, GateState>> next = new ArrayList<>();
            for (Map<String, GateState> base : combinations) {
                for (GateState st : go.states()) {
                    Map<String, GateState> copy = new LinkedHashMap<>(base);
                    copy.put(go.spec().gateCode(), st);
                    next.add(copy);
                }
            }
            combinations = next;
        }

        // 峰值时刻透明化：各管段流量峰值时刻不同也不合成同一工况
        String peakNote = buildPeakNote(path, event);

        List<Scenario> scenarios = new ArrayList<>();
        String pathLabel = String.join("-", path.stream().map(Segment::segmentCode).toList());
        for (Map<String, GateState> combo : combinations) {
            String conditionLabel = combo.isEmpty()
                    ? "BASE"
                    : String.join(";", combo.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue()).toList());
            String scenarioCode = event.eventCode() + "|" + pathLabel + "|" + conditionLabel;

            Scenario existing = scenarioRepo.findByCode(scenarioCode).orElse(null);
            if (existing != null) {
                scenarios.add(existing);
                continue;
            }

            Map<String, Object> boundarySpec = new LinkedHashMap<>();
            boundarySpec.put("downstreamBoundaryCode", downstreamBoundaryCode);
            List<Map<String, Object>> gateSpecs = new ArrayList<>();
            for (GateObs go : gateObsList) {
                Map<String, Object> gs = new LinkedHashMap<>();
                gs.put("gateCode", go.spec().gateCode());
                gs.put("segmentCode", go.spec().segmentCode());
                gs.put("pinnedState", combo.get(go.spec().gateCode()).name());
                gs.put("observedStates", go.states().stream().map(Enum::name).toList());
                gateSpecs.add(gs);
            }
            boundarySpec.put("gates", gateSpecs);
            boundarySpec.put("peakNote", peakNote);
            try {
                long id = scenarioRepo.insert(scenarioCode, eventId, networkVersionId,
                        objectMapper.writeValueAsString(pathSegmentIds),
                        objectMapper.writeValueAsString(boundarySpec), conditionLabel);
                scenarios.add(scenarioRepo.findById(id).orElseThrow());
            } catch (Exception e) {
                throw ApiException.badRequest("情景保存失败: " + e.getMessage());
            }
        }
        return new AssembleResult(scenarios, conflicts, droppedGates);
    }

    /** 各管段流量峰值时刻说明（仅透明展示，不合成工况）。 */
    private String buildPeakNote(List<Segment> path, HydroEvent event) {
        List<String> parts = new ArrayList<>();
        for (Segment s : path) {
            List<Observation> flows = obsRepo.query(Vocab.OBS_FLOW, s.segmentCode(),
                    event.windowStart(), event.windowEnd(), 5000);
            flows.stream()
                    .filter(o -> o.valueNum() != null)
                    .max((a, b) -> Double.compare(a.valueNum(), b.valueNum()))
                    .ifPresent(peak -> parts.add(s.segmentCode() + " 峰值 "
                            + peak.valueNum() + " " + (peak.unit() == null ? "" : peak.unit())
                            + " @ " + peak.sampledAt()));
        }
        if (parts.isEmpty()) {
            return "事件窗口内无流量观测";
        }
        return "各段流量峰值时刻（未对齐的峰值不合成同一工况）: " + String.join("；", parts);
    }

    public List<Scenario> list() {
        return scenarioRepo.list();
    }

    public Scenario findById(long id) {
        return scenarioRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("情景不存在: " + id));
    }
}
