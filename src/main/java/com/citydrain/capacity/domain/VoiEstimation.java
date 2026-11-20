package com.citydrain.capacity.domain;

import java.time.OffsetDateTime;

/** VOI 估计批次：路线（ENUMERATION/SENSITIVITY）+ 评估版本 + as_of + 依据快照。结果不可变。 */
public record VoiEstimation(
        long id,
        String estimationCode,
        long assessmentId,
        String method,
        OffsetDateTime asOf,
        int candidateCount,
        String basisJson,
        OffsetDateTime createdAt
) {}
