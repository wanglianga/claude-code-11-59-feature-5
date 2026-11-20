package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/**
 * 状况评估版本：外部既有评估系统的只读引用快照。
 * 提供决策阈值与各位置风险区间/证据冲突（见 {@link ConditionPositionEntry}）。
 * 本平台不重新评估缺陷、不裁决外部观测，仅按版本保存引用与快照。
 */
public record ConditionAssessmentVersion(
        long id,
        String assessmentCode,
        String sourceSystem,
        String sourceVersion,
        String externalRefIdsJson,
        long networkVersionId,
        String decisionThresholdsJson,
        String payloadJson,
        OffsetDateTime registeredAt
) {}
