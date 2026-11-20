package com.citydrain.capacity;

import com.citydrain.capacity.domain.ExternalRef;
import com.citydrain.capacity.repo.EventRepo;
import com.citydrain.capacity.repo.ExternalRefRepo;
import com.citydrain.capacity.repo.NetworkVersionRepo;
import com.citydrain.capacity.repo.SegmentRepo;
import com.citydrain.capacity.service.voi.VoiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * VOI 测试夹具：四管段网络 + 五类验收样例数据
 * （高风险证据充分 / 低风险近阈值 / 定位模糊 / 维修后复检 / 多点预算竞争）。
 * 节点/编号带 nanoTime 前缀，避免与其他测试在共享库中碰撞。
 */
@Component
public class VoiTestFixtures {

    public static final ZoneOffset CST = ZoneOffset.ofHours(8);

    @Autowired NetworkVersionRepo nvRepo;
    @Autowired SegmentRepo segmentRepo;
    @Autowired ExternalRefRepo extRepo;
    @Autowired EventRepo eventRepo;
    @Autowired VoiService voiService;
    @Autowired ObjectMapper om;

    public long nvId;
    public long segA, segB, segC, segD;
    public long assessmentId;
    public final List<Long> candidateIds = new ArrayList<>();
    public String prefix;

    public long segHighRisk() { return segA; }
    public long segNearThreshold() { return segB; }
    public long segAmbiguous() { return segC; }
    public long segRepaired() { return segD; }

    public void build() {
        prefix = "VT" + System.nanoTime();
        nvId = nvRepo.insert("NV-" + prefix, "VOI 测试网络");
        // V-A 高风险证据充分；V-B 低风险近阈值（兼待验收维修）；V-C 定位模糊；V-D 完整验收维修覆盖
        segA = segmentRepo.insert(nvId, prefix + "-A", "VN1", "VN2", 100, 1000, 5.2, 5.0, 800);
        segB = segmentRepo.insert(nvId, prefix + "-B", "VN2", "VN3", 100, 1000, 5.0, 4.8, 800);
        segC = segmentRepo.insert(nvId, prefix + "-C", "VN3", "VN4", 120, 1200, 4.8, 4.5, 900);
        segD = segmentRepo.insert(nvId, prefix + "-D", "VN4", "VB1", 120, 1200, 4.5, 4.2, 900);

        // 外部只读维修引用：V-D 完整验收（含可追溯实测+维修范围）；V-B 待验收（维修后复检）
        OffsetDateTime sep = OffsetDateTime.of(2026, 9, 1, 9, 0, 0, 0, CST);
        extRepo.insert("REPAIR_ACTION", prefix + "-R-OK", "维修管理系统", "REP-T1", segD, sep,
                "{\"scope\":\"V-D 0-30m 修复\",\"repairedRange\":{\"startM\":0,\"endM\":30},"
                        + "\"acceptance\":{\"result\":\"ACCEPTED\",\"acceptedAt\":\"2026-09-01T09:00:00+08:00\"},"
                        + "\"measuredChanges\":{\"diameterMm\":[1180,1200]},\"measurementRef\":\"MSR-1\"}");
        extRepo.insert("REPAIR_ACTION", prefix + "-R-PENDING", "维修管理系统", "REP-T1", segB, sep,
                "{\"scope\":\"V-B 清淤\",\"repairedRange\":{\"startM\":0,\"endM\":100},"
                        + "\"acceptance\":{\"result\":\"PENDING\"},"
                        + "\"measuredChanges\":{\"siltRatio\":[0.02,0.05]},\"measurementRef\":\"MSR-2\"}");
        List<Long> refIds = extRepo.list(null, null).stream().map(ExternalRef::id).toList();

        // 候选方案
        candidateIds.add(registerCandidate(prefix + "-CAND-A", segA, "CCTV", 3, 0.5, 12000,
                range(0, 100), true, false));
        candidateIds.add(registerCandidate(prefix + "-CAND-B", segB, "QV", 8, 1.0, 9000,
                range(0, 100), true, false));
        // V-C 同位置两种方法：低分辨率 SONAR（供应商不可达）vs 高精度 CCTV
        candidateIds.add(registerCandidate(prefix + "-CAND-C-SONAR", segC, "SONAR", 20, 6.0, 18000,
                range(0, 120), false, false));
        candidateIds.add(registerCandidate(prefix + "-CAND-C-CCTV", segC, "CCTV", 5, 1.0, 16000,
                range(0, 120), true, false));
        // V-D 候选落在完整验收维修范围内（应被排除）
        candidateIds.add(registerCandidate(prefix + "-CAND-D", segD, "CCTV", 3, 0.5, 14000,
                range(0, 30), true, false));
        // 雨季禁入候选（V-B 人工复核，低成本）
        candidateIds.add(registerCandidate(prefix + "-CAND-B-MANUAL", segB, "MANUAL_ENTRY", 50, 5.0, 4000,
                range(0, 100), true, true));

        // 外部状况评估版本快照
        ObjectNode thresholds = om.createObjectNode()
                .put("monitorMin", 40).put("remediateMin", 70)
                .put("remediationMargin", 0.10).put("remainingRiskLps", 200);
        ArrayNode entries = om.createArrayNode();
        entries.addObject()
                .put("riskScoreMin", 78).put("riskScoreMax", 88)
                .put("evidenceConflict", false).put("locationAmbiguityM", 1)
                .put("currentDecision", "REMEDIATE").put("currentRiskLengthM", 40)
                .put("note", "证据充分，区间窄").put("segmentId", segA)
                .set("locationRange", range(0, 100));
        entries.addObject()
                .put("riskScoreMin", 34).put("riskScoreMax", 44)
                .put("evidenceConflict", false).put("locationAmbiguityM", 2)
                .put("currentDecision", "MONITOR").put("currentRiskLengthM", 25)
                .put("note", "中点 39 贴近 MONITOR 阈值 40；待验收维修").put("segmentId", segB)
                .set("locationRange", range(0, 100));
        var cNote = om.createObjectNode().put("adjacentDefectSpacingM", 0.02).put("remark", "相邻缺陷 20mm");
        var cEntry = entries.addObject()
                .put("riskScoreMin", 55).put("riskScoreMax", 75)
                .put("evidenceConflict", true).put("locationAmbiguityM", 8)
                .put("currentDecision", "MONITOR").put("currentRiskLengthM", 50)
                .put("segmentId", segC);
        cEntry.set("note", cNote);
        cEntry.set("locationRange", range(0, 120));
        entries.addObject()
                .put("riskScoreMin", 20).put("riskScoreMax", 30)
                .put("evidenceConflict", false).put("locationAmbiguityM", 1)
                .put("currentDecision", "NO_ACTION").put("currentRiskLengthM", 10)
                .put("note", "维修验收后低风险").put("segmentId", segD)
                .set("locationRange", range(0, 30));

        assessmentId = voiService.registerAssessment("CA-" + prefix, "外部管网评估系统",
                "COND-" + prefix, refIds, nvId, thresholds,
                java.util.stream.StreamSupport.stream(entries.spliterator(), false).toList());
    }

    private long registerCandidate(String code, long segmentId, String method, double resolutionMm,
                                   double locationErrorM, double cost, ObjectNode locationRange,
                                   boolean reachable, boolean rainyBlocked) {
        ArrayNode cats = om.createArrayNode().add("结构缺陷").add("功能性缺陷");
        long id = voiService.registerCandidate(code, segmentId, method, resolutionMm, locationErrorM,
                cost, cats, locationRange, reachable, rainyBlocked, "测试候选");
        return id;
    }

    private ObjectNode range(int start, int end) {
        return om.createObjectNode().put("startM", start).put("endM", end);
    }

    public OffsetDateTime t(int y, int mo, int d, int h, int mi) {
        return OffsetDateTime.of(y, mo, d, h, mi, 0, 0, CST);
    }
}
