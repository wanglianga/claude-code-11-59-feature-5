-- V1 初始 Schema：城市地下管网输送能力区间评估与运行分析平台
-- 资产参数库 / 运行观测库 / 外部只读引用 / 水文事件 / 情景 / 计算运行 / 能力结果 / 覆盖账本

CREATE TABLE network_version (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 管段（名义属性 + 设计需求）；经确认的几何/坡度/糙率/淤积区间在参数版本中
CREATE TABLE pipe_segment (
    id                   BIGSERIAL PRIMARY KEY,
    network_version_id   BIGINT NOT NULL REFERENCES network_version(id),
    segment_code         TEXT NOT NULL,
    from_node            TEXT NOT NULL,
    to_node              TEXT NOT NULL,
    length_m             DOUBLE PRECISION NOT NULL CHECK (length_m > 0),
    shape                TEXT NOT NULL DEFAULT 'CIRCULAR',
    nominal_diameter_mm  DOUBLE PRECISION NOT NULL CHECK (nominal_diameter_mm > 0),
    upstream_invert_m    DOUBLE PRECISION NOT NULL,
    downstream_invert_m  DOUBLE PRECISION NOT NULL,
    design_flow_lps      DOUBLE PRECISION NOT NULL CHECK (design_flow_lps >= 0),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (network_version_id, segment_code)
);

-- 资产参数库：经确认的管段几何、坡度、衬砌/淤积/变形测量区间，各自有效版本
-- 区间字段为 NULL 表示该参数缺失/未知（计算时扩大区间并标记限制来源）
CREATE TABLE segment_parameter_version (
    id              BIGSERIAL PRIMARY KEY,
    segment_id      BIGINT NOT NULL REFERENCES pipe_segment(id),
    version_no      INT  NOT NULL,
    source          TEXT NOT NULL,           -- AS_BUILT / SURVEY / REPAIR_ACCEPTANCE / MANUAL
    diameter_min_mm DOUBLE PRECISION,        -- 有效断面等效直径区间（含变形）
    diameter_max_mm DOUBLE PRECISION,
    slope_min       DOUBLE PRECISION,        -- 坡度区间（小数，如 0.002）
    slope_max       DOUBLE PRECISION,
    roughness_min   DOUBLE PRECISION,        -- 糙率 n 区间，NULL=缺失
    roughness_max   DOUBLE PRECISION,
    silt_ratio_min  DOUBLE PRECISION,        -- 淤积面积占比区间 [0,1]，NULL=未知
    silt_ratio_max  DOUBLE PRECISION,
    valid_from      TIMESTAMPTZ NOT NULL,
    valid_to        TIMESTAMPTZ,             -- NULL = 当前有效
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      TEXT,
    repair_ref_id   BIGINT,                  -- 由维修验收派生时指向 external_ref
    note            TEXT,
    UNIQUE (segment_id, version_no),
    CHECK (diameter_min_mm IS NULL OR diameter_max_mm IS NULL OR diameter_min_mm <= diameter_max_mm),
    CHECK (slope_min IS NULL OR slope_max IS NULL OR slope_min <= slope_max),
    CHECK (roughness_min IS NULL OR roughness_max IS NULL OR roughness_min <= roughness_max),
    CHECK (silt_ratio_min IS NULL OR silt_ratio_max IS NULL OR silt_ratio_min <= silt_ratio_max)
);

-- 运行观测库：流量、水位、降雨、下游边界、闸门状态、采样时间与校准信息
-- ingested_at 为入库时间（迟到补录晚于 sampled_at），历史复算按 ingested_at <= as_of 判定可见性
CREATE TABLE observation (
    id                 BIGSERIAL PRIMARY KEY,
    obs_type           TEXT NOT NULL,        -- FLOW / WATER_LEVEL / RAINFALL / DOWNSTREAM_LEVEL / GATE_STATE
    target_type        TEXT NOT NULL,        -- SEGMENT / NODE / GATE / BOUNDARY
    target_code        TEXT NOT NULL,        -- 管段号 / 节点号 / 闸门号 / 边界点号
    value_num          DOUBLE PRECISION,
    value_text         TEXT,                 -- 闸门状态 OPEN / CLOSED / PARTIAL
    unit               TEXT,
    sampled_at         TIMESTAMPTZ NOT NULL,
    ingested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    source             TEXT,
    source_version     TEXT,
    calibration_status TEXT NOT NULL DEFAULT 'RAW'   -- RAW / CALIBRATED
);
CREATE INDEX idx_obs_target ON observation (target_code, obs_type, sampled_at);
CREATE INDEX idx_obs_ingested ON observation (ingested_at);
CREATE INDEX idx_obs_type_time ON observation (obs_type, sampled_at);

-- 水文事件：同一事件的观测组合边界；时间未对齐的峰值不得合成为同一事件
CREATE TABLE hydro_event (
    id           BIGSERIAL PRIMARY KEY,
    event_code   TEXT NOT NULL UNIQUE,
    name         TEXT,
    window_start TIMESTAMPTZ NOT NULL,
    window_end   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (window_start < window_end)
);

-- 外部业务系统只读引用：缺陷观测 / 检查覆盖 / 维修作用范围与验收结果
-- 本平台只保存引用及来源版本，不重新定位缺陷、不裁决观测、不推导覆盖与维修状态
CREATE TABLE external_ref (
    id            BIGSERIAL PRIMARY KEY,
    ref_type      TEXT NOT NULL,             -- DEFECT_OBSERVATION / INSPECTION_COVERAGE / REPAIR_ACTION
    external_id   TEXT NOT NULL,
    source_system TEXT NOT NULL,
    source_version TEXT NOT NULL,
    segment_id    BIGINT REFERENCES pipe_segment(id),
    observed_at   TIMESTAMPTZ,
    payload       JSONB NOT NULL,            -- 位置范围/类别等级/形态属性 或 覆盖范围 或 维修范围+验收+实测变化
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (ref_type, external_id, source_version)
);

-- 情景：同一水文事件 + 管网版本 + 路径 + 边界；闸门冲突时拆分为多个条件情景（不取平均）
-- boundary_spec 保存“取值规则”（事件窗口、边界点、闸门钉定状态），具体数值在计算时按 as_of 解析
CREATE TABLE scenario (
    id                BIGSERIAL PRIMARY KEY,
    scenario_code     TEXT NOT NULL UNIQUE,
    event_id          BIGINT NOT NULL REFERENCES hydro_event(id),
    network_version_id BIGINT NOT NULL REFERENCES network_version(id),
    path_segment_ids  JSONB NOT NULL,        -- 有序管段 id 列表（上游→下游）
    boundary_spec     JSONB NOT NULL,
    condition_label   TEXT NOT NULL DEFAULT 'BASE',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 计算运行：方法 + as_of 历史时点；同一 (scenario, method, as_of) 幂等
CREATE TABLE computation_run (
    id              BIGSERIAL PRIMARY KEY,
    run_code        TEXT NOT NULL UNIQUE,
    scenario_id     BIGINT NOT NULL REFERENCES scenario(id),
    method          TEXT NOT NULL,           -- INTERVAL_PROPAGATION / CALIBRATED_ENSEMBLE
    as_of           TIMESTAMPTZ NOT NULL,
    status          TEXT NOT NULL DEFAULT 'COMPLETED',
    network_summary JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_run_key ON computation_run (scenario_id, method, as_of);

-- 能力评估结果：每段可通过流量区间、相对设计需求余量、主导不确定参数、不可判定原因、证据
CREATE TABLE capacity_result (
    id                    BIGSERIAL PRIMARY KEY,
    run_id                BIGINT NOT NULL REFERENCES computation_run(id) ON DELETE CASCADE,
    segment_id            BIGINT NOT NULL REFERENCES pipe_segment(id),
    q_min_lps             DOUBLE PRECISION,
    q_max_lps             DOUBLE PRECISION,
    margin_min            DOUBLE PRECISION,   -- (q_min - 设计需求)/设计需求
    margin_max            DOUBLE PRECISION,
    dominant_uncertainty  JSONB,              -- [{parameter, contributionPct}]
    limitations           JSONB,              -- [ROUGHNESS_MISSING, DOWNSTREAM_LEVEL_MISSING, ...]
    undeterminable_reason TEXT,               -- 不可判定原因（NULL=可判定）
    evidence              JSONB,              -- 参数版本/观测 id/假设/外部引用 id，供证据回查
    UNIQUE (run_id, segment_id)
);

-- 覆盖账本：本次计算中参数和观测的可用性
CREATE TABLE coverage_ledger (
    id         BIGSERIAL PRIMARY KEY,
    run_id     BIGINT NOT NULL REFERENCES computation_run(id) ON DELETE CASCADE,
    segment_id BIGINT REFERENCES pipe_segment(id),
    item       TEXT NOT NULL,     -- PARAM_VERSION / PARAM_GEOMETRY / PARAM_SLOPE / PARAM_ROUGHNESS / PARAM_SILTATION
                                  -- OBS_DOWNSTREAM_LEVEL / OBS_GATE_STATE / OBS_FLOW / CALIBRATION_SAMPLES
                                  -- EXT_DEFECT_REF / EXT_INSPECTION_COVERAGE_REF
    status     TEXT NOT NULL,     -- AVAILABLE / ASSUMED / MISSING / CONFLICTED
    detail     TEXT
);
CREATE INDEX idx_ledger_run ON coverage_ledger (run_id);
