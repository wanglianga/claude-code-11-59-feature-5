package com.citydrain.capacity.service.voi;

import com.citydrain.capacity.domain.ExternalRef;
import com.citydrain.capacity.domain.Vocab;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 维修作用范围解析（依据 external_ref 只读引用，不重新推导维修状态）：
 * - 完整验收覆盖：REPAIR_ACTION 且 acceptance.result=ACCEPTED、含 acceptedAt、measurementRef 可追溯，
 *   且维修范围与候选覆盖位置重叠 → 该区域不重复推荐复检；
 * - 待验收维修：维修已实施但无完整验收 → “维修后复检”场景，修复结果概率上调。
 */
@Component
public class RepairCoverageResolver {

    private final ObjectMapper objectMapper;

    public RepairCoverageResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record Coverage(boolean repairedAcceptedCovered, boolean pendingRepair, String detail) {}

    public Coverage resolve(List<ExternalRef> repairRefs, double candidateStartM, double candidateEndM) {
        boolean accepted = false;
        boolean pending = false;
        StringBuilder detail = new StringBuilder();
        for (ExternalRef ref : repairRefs) {
            JsonNode payload;
            try {
                payload = objectMapper.readTree(ref.payloadJson());
            } catch (Exception e) {
                continue;
            }
            JsonNode acceptance = payload.path("acceptance");
            String result = acceptance.path("result").asText("");
            String acceptedAt = acceptance.path("acceptedAt").asText("");
            String measurementRef = payload.path("measurementRef").asText("");
            double[] range = parseRange(payload, ref.payloadJson());
            boolean overlaps = range == null || overlap(candidateStartM, candidateEndM, range[0], range[1]);
            if (!overlaps) {
                continue;
            }
            if ("ACCEPTED".equalsIgnoreCase(result) && !acceptedAt.isBlank() && !measurementRef.isBlank()) {
                accepted = true;
                append(detail, "完整验收维修 " + ref.externalId() + "（" + measurementRef + "）覆盖候选位置");
            } else if ("PENDING".equalsIgnoreCase(result)) {
                pending = true;
                append(detail, "待验收维修 " + ref.externalId() + "，属维修后复检场景");
            } else if ("ACCEPTED".equalsIgnoreCase(result) && measurementRef.isBlank()) {
                // 已验收但实测不可追溯：既不构成完整验收覆盖，也不是待验收；仅透明记录
                append(detail, "维修 " + ref.externalId() + " 已验收但缺 measurementRef，不满足完整验收覆盖条件");
            }
        }
        return new Coverage(accepted, pending, detail.toString());
    }

    private void append(StringBuilder sb, String text) {
        if (!sb.isEmpty()) {
            sb.append("；");
        }
        sb.append(text);
    }

    /** 优先读 payload.repairedRange；其次从 scope 文本解析 "45-62m"；无法解析视为全段覆盖。 */
    private double[] parseRange(JsonNode payload, String raw) {
        JsonNode rr = payload.path("repairedRange");
        if (rr.isObject() && rr.path("startM").isNumber() && rr.path("endM").isNumber()) {
            return new double[]{rr.path("startM").asDouble(), rr.path("endM").asDouble()};
        }
        // 仅用于种子/外部文本的兜底解析
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)\\s*[-~至]\\s*(\\d+(?:\\.\\d+)?)\\s*m").matcher(raw);
        if (m.find()) {
            return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
        }
        return null;
    }

    private boolean overlap(double aStart, double aEnd, double bStart, double bEnd) {
        return aStart <= bEnd && bStart <= aEnd;
    }

    public static String refType() {
        return Vocab.REF_REPAIR;
    }
}
