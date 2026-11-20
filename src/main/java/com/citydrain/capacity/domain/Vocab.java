package com.citydrain.capacity.domain;

/**
 * 开放词表常量：观测类型、目标类型、外部引用类型、覆盖账本条目、限制来源标记。
 * 使用 String 常量而非枚举，便于外部系统扩展取值。
 */
public final class Vocab {

    private Vocab() {}

    // 观测类型
    public static final String OBS_FLOW = "FLOW";                       // 流量
    public static final String OBS_WATER_LEVEL = "WATER_LEVEL";         // 水位（节点/管段）
    public static final String OBS_RAINFALL = "RAINFALL";               // 降雨
    public static final String OBS_DOWNSTREAM_LEVEL = "DOWNSTREAM_LEVEL"; // 下游边界水位
    public static final String OBS_GATE_STATE = "GATE_STATE";           // 闸门状态

    // 观测目标类型
    public static final String TARGET_SEGMENT = "SEGMENT";
    public static final String TARGET_NODE = "NODE";
    public static final String TARGET_GATE = "GATE";
    public static final String TARGET_BOUNDARY = "BOUNDARY";

    // 外部引用类型（只读接入）
    public static final String REF_DEFECT = "DEFECT_OBSERVATION";       // 缺陷观测
    public static final String REF_INSPECTION = "INSPECTION_COVERAGE";  // 检查覆盖
    public static final String REF_REPAIR = "REPAIR_ACTION";            // 维修作用范围/验收结果

    // 覆盖账本条目
    public static final String ITEM_PARAM_VERSION = "PARAM_VERSION";
    public static final String ITEM_PARAM_GEOMETRY = "PARAM_GEOMETRY";
    public static final String ITEM_PARAM_SLOPE = "PARAM_SLOPE";
    public static final String ITEM_PARAM_ROUGHNESS = "PARAM_ROUGHNESS";
    public static final String ITEM_PARAM_SILTATION = "PARAM_SILTATION";
    public static final String ITEM_OBS_DOWNSTREAM_LEVEL = "OBS_DOWNSTREAM_LEVEL";
    public static final String ITEM_OBS_GATE_STATE = "OBS_GATE_STATE";
    public static final String ITEM_OBS_FLOW = "OBS_FLOW";
    public static final String ITEM_CALIBRATION_SAMPLES = "CALIBRATION_SAMPLES";
    public static final String ITEM_EXT_DEFECT_REF = "EXT_DEFECT_REF";
    public static final String ITEM_EXT_INSPECTION_REF = "EXT_INSPECTION_COVERAGE_REF";

    // 覆盖账本状态
    public static final String ST_AVAILABLE = "AVAILABLE";
    public static final String ST_ASSUMED = "ASSUMED";
    public static final String ST_MISSING = "MISSING";
    public static final String ST_CONFLICTED = "CONFLICTED";

    // 限制来源标记（写入 capacity_result.limitations）
    public static final String LIM_ROUGHNESS_MISSING = "ROUGHNESS_MISSING";
    public static final String LIM_DOWNSTREAM_LEVEL_MISSING = "DOWNSTREAM_LEVEL_MISSING";
    public static final String LIM_SILTATION_MISSING = "SILTATION_MISSING";
    public static final String LIM_BACKWATER_DOMINATED = "BACKWATER_DOMINATED";
    public static final String LIM_GATE_CLOSED = "GATE_CLOSED";
    public static final String LIM_GATE_PARTIAL = "GATE_PARTIAL";
    public static final String LIM_CALIBRATION_INSUFFICIENT = "CALIBRATION_INSUFFICIENT";

    // 不可判定原因
    public static final String REASON_NO_PARAM_VERSION = "NO_PARAMETER_VERSION_AT_AS_OF";
    public static final String REASON_GEOMETRY_MISSING = "GEOMETRY_MISSING";
    public static final String REASON_SLOPE_MISSING = "SLOPE_MISSING";

    // ===== 复检信息价值（VOI） =====

    // VOI 估计路线（两路线产出同一口径 evi，互不作为对方的预处理）
    public static final String VOI_ENUMERATION = "ENUMERATION";   // 结果枚举法：离散结果 × 概率加权
    public static final String VOI_SENSITIVITY = "SENSITIVITY";   // 局部敏感度法：观测不确定度上的近似筛选

    // 复检候选状态
    public static final String CAND_ACTIVE = "ACTIVE";
    public static final String CAND_WITHDRAWN = "WITHDRAWN";

    // 处置决定（来自外部状况评估版本，本平台不自行裁决）
    public static final String DEC_REMEDIATE = "REMEDIATE";
    public static final String DEC_MONITOR = "MONITOR";
    public static final String DEC_NO_ACTION = "NO_ACTION";

    // 枚举法可能结果
    public static final String OUT_WORSE = "FIND_WORSE";          // 发现更严重
    public static final String OUT_CONFIRM = "CONFIRM_CURRENT";   // 确认现状
    public static final String OUT_REPAIRED = "FIND_REPAIRED";    // 发现已修复

    // 低价值原因
    public static final String LV_DECISION_IMMUTABLE = "DECISION_IMMUTABLE";                 // 任何结果都无法改变决定
    public static final String LV_RESOLUTION_INSUFFICIENT = "RESOLUTION_INSUFFICIENT";       // 分辨率不足以区分相邻缺陷
    public static final String LV_REPAIRED_ACCEPTED = "REPAIRED_ACCEPTED_COVERAGE";          // 已维修且完整验收覆盖
    public static final String LV_OUTSIDE_ASSESSMENT = "NOT_COVERED_BY_ASSESSMENT";          // 评估版本未覆盖该位置

    // 计划明细原因
    public static final String PI_SELECTED = "SELECTED";
    public static final String PI_BUDGET = "BUDGET_COMPETITION";
    public static final String PI_SUPPLIER = "SUPPLIER_UNREACHABLE";
    public static final String PI_RAINY = "RAINY_SEASON_BLOCKED";
    public static final String PI_LOW_VALUE = "LOW_VALUE";
    public static final String PI_REPAIRED = "REPAIRED_ACCEPTED";
}
