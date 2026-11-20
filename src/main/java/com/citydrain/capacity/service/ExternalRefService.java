package com.citydrain.capacity.service;

import com.citydrain.capacity.domain.ExternalRef;
import com.citydrain.capacity.domain.ParamVersion;
import com.citydrain.capacity.domain.Vocab;
import com.citydrain.capacity.repo.ExternalRefRepo;
import com.citydrain.capacity.repo.ParamVersionRepo;
import com.citydrain.capacity.repo.SegmentRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 外部业务系统只读引用服务。
 * 平台只保存引用及来源版本：不重新定位缺陷、不裁决观测、不推导覆盖与维修状态。
 * 唯一允许的派生：维修记录在有验收且实测参数变化可追溯时形成新参数版本（旧版本保留）。
 */
@Service
public class ExternalRefService {

    private static final Set<String> REF_TYPES = Set.of(
            Vocab.REF_DEFECT, Vocab.REF_INSPECTION, Vocab.REF_REPAIR);

    private final ExternalRefRepo extRepo;
    private final ParamVersionRepo paramRepo;
    private final SegmentRepo segmentRepo;
    private final ObjectMapper objectMapper;

    public ExternalRefService(ExternalRefRepo extRepo, ParamVersionRepo paramRepo,
                              SegmentRepo segmentRepo, ObjectMapper objectMapper) {
        this.extRepo = extRepo;
        this.paramRepo = paramRepo;
        this.segmentRepo = segmentRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public long register(String refType, String externalId, String sourceSystem, String sourceVersion,
                         Long segmentId, OffsetDateTime observedAt, JsonNode payload) {
        if (!REF_TYPES.contains(refType)) {
            throw ApiException.badRequest("非法 refType: " + refType);
        }
        if (segmentId != null) {
            segmentRepo.findById(segmentId)
                    .orElseThrow(() -> ApiException.notFound("管段不存在: " + segmentId));
        }
        try {
            return extRepo.insert(refType, externalId, sourceSystem, sourceVersion,
                    segmentId, observedAt, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                throw ApiException.conflict("相同 (refType, externalId, sourceVersion) 的引用已登记");
            }
            throw ApiException.badRequest("payload 序列化失败: " + e.getMessage());
        }
    }

    public List<ExternalRef> list(String refType, Long segmentId) {
        return extRepo.list(refType, segmentId);
    }

    public Optional<ExternalRef> findById(long id) {
        return extRepo.findById(id);
    }

    /**
     * 维修记录 → 新参数版本。仅当：
     * 1) 有验收（payload.acceptance.result == ACCEPTED 且含 acceptedAt）；
     * 2) 实测参数变化可追溯（payload.measuredChanges 至少一项区间 + measurementRef 非空）。
     * 旧版本在 acceptedAt 关闭但保留（不自动抹去旧测量）；未实测项继承上一版本区间。
     */
    @Transactional
    public ParamVersion deriveParameterVersionFromRepair(long refId, String actor) {
        ExternalRef ref = extRepo.findById(refId)
                .orElseThrow(() -> ApiException.notFound("外部引用不存在: " + refId));
        if (!Vocab.REF_REPAIR.equals(ref.refType())) {
            throw ApiException.unprocessable("仅 REPAIR_ACTION 类型引用可派生参数版本");
        }
        if (ref.segmentId() == null) {
            throw ApiException.unprocessable("维修引用未关联管段，无法派生参数版本");
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(ref.payloadJson());
        } catch (Exception e) {
            throw ApiException.unprocessable("维修引用 payload 无法解析");
        }

        // 条件1：有验收
        JsonNode acceptance = payload.path("acceptance");
        String result = acceptance.path("result").asText("");
        String acceptedAtText = acceptance.path("acceptedAt").asText("");
        if (!"ACCEPTED".equalsIgnoreCase(result) || acceptedAtText.isBlank()) {
            throw ApiException.unprocessable(
                    "维修记录无有效验收（acceptance.result 须为 ACCEPTED 且含 acceptedAt），不形成新参数版本");
        }
        OffsetDateTime acceptedAt;
        try {
            acceptedAt = OffsetDateTime.parse(acceptedAtText);
        } catch (Exception e) {
            throw ApiException.unprocessable("acceptance.acceptedAt 无法解析为时间: " + acceptedAtText);
        }

        // 条件2：实测参数变化可追溯
        JsonNode changes = payload.path("measuredChanges");
        String measurementRef = payload.path("measurementRef").asText("");
        if (measurementRef.isBlank()) {
            throw ApiException.unprocessable("缺少 measurementRef，实测参数变化不可追溯，不形成新参数版本");
        }
        Double[] diameter = readRange(changes, "diameterMm");
        Double[] slope = readRange(changes, "slope");
        Double[] roughness = readRange(changes, "roughness");
        Double[] silt = readRange(changes, "siltRatio");
        if (diameter == null && slope == null && roughness == null && silt == null) {
            throw ApiException.unprocessable(
                    "measuredChanges 未包含任何实测参数区间（diameterMm/slope/roughness/siltRatio），不形成新参数版本");
        }

        long segmentId = ref.segmentId();
        Optional<ParamVersion> open = paramRepo.findOpenVersion(segmentId);
        if (open.isPresent() && !acceptedAt.isAfter(open.get().validFrom())) {
            throw ApiException.unprocessable("验收时间 " + acceptedAtText
                    + " 不晚于上一版本 validFrom " + open.get().validFrom() + "，无法形成有效版本窗");
        }

        // 未实测项继承上一版本（版本链内正常继承，非历史回填）
        ParamVersion prev = open.orElse(null);
        ParamVersion v = new ParamVersion(
                0, segmentId, paramRepo.nextVersionNo(segmentId), "REPAIR_ACCEPTANCE",
                diameter != null ? diameter[0] : (prev != null ? prev.diameterMinMm() : null),
                diameter != null ? diameter[1] : (prev != null ? prev.diameterMaxMm() : null),
                slope != null ? slope[0] : (prev != null ? prev.slopeMin() : null),
                slope != null ? slope[1] : (prev != null ? prev.slopeMax() : null),
                roughness != null ? roughness[0] : (prev != null ? prev.roughnessMin() : null),
                roughness != null ? roughness[1] : (prev != null ? prev.roughnessMax() : null),
                silt != null ? silt[0] : (prev != null ? prev.siltRatioMin() : null),
                silt != null ? silt[1] : (prev != null ? prev.siltRatioMax() : null),
                acceptedAt, null, OffsetDateTime.now(), actor, ref.id(),
                "由维修验收派生（measurementRef=" + measurementRef + "，外部引用 #" + ref.id()
                        + " " + ref.sourceSystem() + "@" + ref.sourceVersion() + "）");
        long id = paramRepo.insert(v);
        if (prev != null) {
            paramRepo.closeVersion(prev.id(), acceptedAt);
        }
        return paramRepo.findById(id).orElseThrow();
    }

    /** 读取 [min,max] 区间；缺失返回 null；非法抛 422。 */
    private Double[] readRange(JsonNode changes, String field) {
        JsonNode node = changes.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isArray() || node.size() != 2 || !node.get(0).isNumber() || !node.get(1).isNumber()) {
            throw ApiException.unprocessable("measuredChanges." + field + " 须为 [min,max] 数值数组");
        }
        double lo = node.get(0).asDouble();
        double hi = node.get(1).asDouble();
        if (lo > hi) {
            throw ApiException.unprocessable("measuredChanges." + field + " 下界大于上界");
        }
        return new Double[]{lo, hi};
    }
}
