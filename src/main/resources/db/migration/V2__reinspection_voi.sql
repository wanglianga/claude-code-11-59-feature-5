-- V2 复检信息价值（VOI）估计能力
-- 复检候选方案 / 状况评估版本（外部评估系统只读引用快照）/ VOI 估计批次与结果 / 复检计划版本与明细
-- 设计约束：
--   * 缺陷状况、检查覆盖、维修作用范围仍以 external_ref 只读引用为准，本模块只保存引用快照与来源版本，不回写外部系统
--   * 预算/供应商可达性/雨季禁入变化后生成新计划版本，旧计划保留当时依据（计划版本不可变，无更新/删除接口）
--   * 已被维修覆盖且有完整验收的区域不重复推荐
--   * 两条估计路线（结果枚举 ENUMERATION / 局部敏感度 SENSITIVITY）产出同一口径的预期信息价值 evi

-- 复检候选方案：可覆盖位置、方法分辨率、预计定位误差、成本、可发现缺陷类别、供应商可达性、雨季禁入
CREATE TABLE reinspection_candidate (
    id                     BIGSERIAL PRIMARY KEY,
    candidate_code         TEXT NOT NULL UNIQUE,
    segment_id             BIGINT NOT NULL REFERENCES pipe_segment(id),
    method_code            TEXT NOT NULL,            -- CCTV / SONAR / QV / MANUAL_ENTRY 等
    method_resolution_mm   DOUBLE PRECISION NOT NULL CHECK (method_resolution_mm > 0),  -- 方法分辨率（越小越能区分相邻缺陷）
    location_error_m       DOUBLE PRECISION NOT NULL CHECK (location_error_m >= 0),     -- 预计定位误差
    cost                   DOUBLE PRECISION NOT NULL CHECK (cost >= 0),
    detectable_categories  JSONB NOT NULL,           -- ["结构缺陷","功能性缺陷",...]
    location_range         JSONB NOT NULL,           -- {"startM":..,"endM":..} 候选可覆盖位置范围
    supplier_reachable     BOOLEAN NOT NULL DEFAULT TRUE,
    rainy_season_blocked   BOOLEAN NOT NULL DEFAULT FALSE,
    status                 TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / WITHDRAWN（供应商退出等，仅追加状态）
    note                   TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_candidate_segment ON reinspection_candidate (segment_id);

-- 状况评估版本：引用外部既有评估系统（只读引用），提供各位置风险区间、证据冲突与决策阈值
-- 平台只登记“引用 + 版本化快照”，不重新评估缺陷、不裁决外部观测
CREATE TABLE condition_assessment_version (
    id                        BIGSERIAL PRIMARY KEY,
    assessment_code           TEXT NOT NULL UNIQUE,
    source_system             TEXT NOT NULL,
    source_version            TEXT NOT NULL,
    external_ref_ids          JSONB NOT NULL,         -- 依据的 external_ref id 列表（缺陷/覆盖/维修只读引用）
    network_version_id        BIGINT NOT NULL REFERENCES network_version(id),
    decision_thresholds       JSONB NOT NULL,         -- {"remediationMargin":..,"riskScore":..,"remainingRiskLps":..}
    payload                   JSONB NOT NULL,         -- 各位置风险区间/证据冲突/阈值上下文原始快照
    registered_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_system, source_version)
);

-- 位置状况条目：评估版本内每个管段位置的风险区间、证据冲突与当前处置决定
CREATE TABLE condition_position_entry (
    id                     BIGSERIAL PRIMARY KEY,
    assessment_id          BIGINT NOT NULL REFERENCES condition_assessment_version(id) ON DELETE CASCADE,
    segment_id             BIGINT NOT NULL REFERENCES pipe_segment(id),
    location_range         JSONB NOT NULL,           -- {"startM":..,"endM":..}
    risk_score_min         DOUBLE PRECISION NOT NULL, -- 风险指数区间 [0,100]
    risk_score_max         DOUBLE PRECISION NOT NULL,
    evidence_conflict      BOOLEAN NOT NULL DEFAULT FALSE,  -- 证据冲突（多源结论不一致）
    location_ambiguity_m   DOUBLE PRECISION NOT NULL DEFAULT 0, -- 定位模糊范围（米）
    current_decision       TEXT NOT NULL,            -- REMEDIATE / MONITOR / NO_ACTION
    current_risk_length_m  DOUBLE PRECISION NOT NULL, -- 当前估计受风险影响管长
    note                   TEXT,
    CHECK (risk_score_min >= 0 AND risk_score_max <= 100 AND risk_score_min <= risk_score_max),
    UNIQUE (assessment_id, segment_id)
);
CREATE INDEX idx_condition_entry_assessment ON condition_position_entry (assessment_id);

-- VOI 估计批次：一次估计请求（路线 + 候选集合 + 评估版本 + as_of），结果不可变
CREATE TABLE voi_estimation (
    id                     BIGSERIAL PRIMARY KEY,
    estimation_code        TEXT NOT NULL UNIQUE,
    assessment_id          BIGINT NOT NULL REFERENCES condition_assessment_version(id),
    method                 TEXT NOT NULL,            -- ENUMERATION / SENSITIVITY
    as_of                  TIMESTAMPTZ NOT NULL,
    candidate_count        INT NOT NULL,
    basis_json             JSONB NOT NULL,           -- 估计时依据：候选/评估版本/阈值快照与方法选择理由
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_voi_assessment ON voi_estimation (assessment_id);
CREATE UNIQUE INDEX uq_voi_estimation_key ON voi_estimation (assessment_id, method, as_of);

-- VOI 估计结果（每候选一条）：同一口径的预期信息价值、决策变化概率、可缩小范围与低价值标记
CREATE TABLE voi_estimation_result (
    id                        BIGSERIAL PRIMARY KEY,
    estimation_id             BIGINT NOT NULL REFERENCES voi_estimation(id) ON DELETE CASCADE,
    candidate_id              BIGINT NOT NULL REFERENCES reinspection_candidate(id),
    segment_id                BIGINT NOT NULL REFERENCES pipe_segment(id),
    evi                       DOUBLE PRECISION NOT NULL,  -- 预期信息价值（[0,1]，两路线同一口径）
    evi_rank                  INT NOT NULL,
    decision_change_prob      DOUBLE PRECISION NOT NULL,  -- 复检后处置决定发生变化的概率
    low_value                 BOOLEAN NOT NULL DEFAULT FALSE,
    low_value_reasons         JSONB NOT NULL,          -- ["DECISION_IMMUTABLE",...]
    resolution_penalty        DOUBLE PRECISION NOT NULL,-- 分辨率不足降权系数 [0,1]
    excluded_repaired         BOOLEAN NOT NULL DEFAULT FALSE, -- 已维修且完整验收 → 不推荐
    outcome_breakdown         JSONB,                   -- 枚举法：各可能结果的概率/后验决定/收益明细
    sensitivity_detail        JSONB,                   -- 敏感度法：局部敏感度与阈值距离明细
    expected_risk_reduction_m DOUBLE PRECISION NOT NULL,   -- 预期剩余风险长度缩小（米）
    risk_length_narrowing     JSONB,                   -- 剩余风险长度可缩小范围 [min,max]（米）
    network_conclusion_narrowing JSONB,                -- 网络影响结论可缩小范围（瓶颈变化概率/区间宽度缩减）
    CHECK (evi >= 0 AND evi <= 1),
    UNIQUE (estimation_id, candidate_id)
);
CREATE INDEX idx_voi_result_estimation ON voi_estimation_result (estimation_id, evi_rank);

-- 复检计划版本：预算/供应商可达性/雨季禁入约束下的组合选择；约束变化 → 新版本，旧版本保留
CREATE TABLE reinspection_plan (
    id                     BIGSERIAL PRIMARY KEY,
    plan_code              TEXT NOT NULL,
    plan_version           INT NOT NULL,
    estimation_id          BIGINT NOT NULL REFERENCES voi_estimation(id),
    budget                 DOUBLE PRECISION NOT NULL CHECK (budget >= 0),
    supplier_only_reachable BOOLEAN NOT NULL DEFAULT TRUE,
    rainy_season_window    JSONB,                     -- {"start":"...","end":"..."} 雨季禁入窗口
    total_cost             DOUBLE PRECISION NOT NULL,
    total_evi              DOUBLE PRECISION NOT NULL,
    constraint_snapshot    JSONB NOT NULL,           -- 生成时约束快照（保留当时依据）
    supersedes_plan_id     BIGINT REFERENCES reinspection_plan(id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (plan_code, plan_version)
);

-- 计划明细：入选/落选候选及落选原因（预算竞争/不可达/雨季禁入/低价值/已维修覆盖）
CREATE TABLE reinspection_plan_item (
    id                     BIGSERIAL PRIMARY KEY,
    plan_id                BIGINT NOT NULL REFERENCES reinspection_plan(id) ON DELETE CASCADE,
    candidate_id           BIGINT NOT NULL REFERENCES reinspection_candidate(id),
    segment_id             BIGINT NOT NULL REFERENCES pipe_segment(id),
    selected               BOOLEAN NOT NULL,
    evi                    DOUBLE PRECISION NOT NULL,
    cost                   DOUBLE PRECISION NOT NULL,
    expected_risk_reduction_m DOUBLE PRECISION NOT NULL,
    decision_change_prob   DOUBLE PRECISION NOT NULL,
    reason                 TEXT NOT NULL,            -- SELECTED / BUDGET_COMPETITION / SUPPLIER_UNREACHABLE /
                                                     -- RAINY_SEASON_BLOCKED / LOW_VALUE / REPAIRED_ACCEPTED
    detail                 TEXT,
    UNIQUE (plan_id, candidate_id)
);
CREATE INDEX idx_plan_item_plan ON reinspection_plan_item (plan_id);
