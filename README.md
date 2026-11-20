# 城市地下管网输送能力区间评估与运行分析平台

面向市政排水运行团队：在不同降雨、下游水位和闸门状态下，评估现有管段**还可承载多少流量**。
平台不把名义管径直接代入公式，而是以**经确认的测量区间**（有效断面、坡度、糙率、淤积/变形）
逐段传播不确定性，输出**可通过流量区间**、相对设计需求的余量、主导不确定参数与不可判定原因；
不同工况的峰值不会被拼成并不存在的瓶颈。

- 技术栈：Java 25 · Spring Boot 3.5 · PostgreSQL 18（Flyway 迁移）· springdoc-openapi（Swagger UI）
- 交付形态：后端平台（REST API + Swagger UI），含 Dockerfile 与 docker-compose 一键启动

---

## 1. 快速启动

### 1.1 Docker Compose（一键）

```bash
docker compose up --build
```

- 应用：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui/index.html
- OpenAPI 文档：http://localhost:8080/v3/api-docs
- PostgreSQL：localhost:5432（库/用户/密码均为 `ccdev`）

首次启动自动执行 Flyway 建表并写入种子数据（覆盖全部验收场景）。

### 1.2 本地开发

```bash
# 需要：JDK 25、Maven 3.9+、PostgreSQL（127.0.0.1:5432，库/用户/密码 ccdev）
mvn spring-boot:run          # 启动应用（自动建表+种子数据）
mvn test                     # 运行 30 个集成测试（需 PostgreSQL 在线）
```

配置项（环境变量）：`DB_URL`（默认 `jdbc:postgresql://127.0.0.1:5432/ccdev`）、
`DB_USER`/`DB_PASSWORD`（默认 `ccdev`）、`SERVER_PORT`（默认 8080）、`SEED_ENABLED`（默认 true）。

### 1.3 测试账号（HTTP Basic）

| 账号 | 密码 | 角色 | 权限 |
| --- | --- | --- | --- |
| `operator` | `operator123` | OPERATOR | 全部读写接口 |
| `viewer` | `viewer123` | VIEWER | 仅 GET 查询 |

Swagger UI 与 `/v3/api-docs` 无需凭据；`/api/**` 需要 Basic Auth。

---

## 2. 领域模型

| 库 | 表 | 内容 |
| --- | --- | --- |
| 资产参数库 | `network_version` / `pipe_segment` / `segment_parameter_version` | 管网版本、管段名义属性与设计需求；**经确认的**有效直径/坡度/糙率/淤积测量区间，按版本与有效时间窗管理 |
| 运行观测库 | `observation` | 流量/水位/降雨/下游边界/闸门状态，含采样时间 `sampled_at`、入库时间 `ingested_at`、校准信息 |
| 外部只读引用 | `external_ref` | 缺陷观测（位置范围/类别等级/形态属性）、检查覆盖、维修作用范围与验收结果；**只存引用及来源版本** |
| 水文事件 | `hydro_event` | 事件窗口；同一事件的观测组合边界 |
| 情景 | `scenario` | 事件 + 管网版本 + 路径 + 边界取值规则；闸门冲突拆分为条件情景 |
| 计算 | `computation_run` / `capacity_result` | 方法 + as_of 的运行；每段流量区间、余量、主导不确定参数、不可判定原因、证据 |
| 覆盖账本 | `coverage_ledger` | 本次计算中每个参数/观测的可用性（AVAILABLE/ASSUMED/MISSING/CONFLICTED） |
| 复检候选 | `reinspection_candidate` | 候选复检方案：可覆盖位置、方法分辨率、预计定位误差、成本、可发现缺陷类别、供应商可达性、雨季禁入 |
| 外部状况评估版本 | `condition_assessment_version` / `condition_position_entry` | 外部评估系统只读引用快照：各位置风险区间、证据冲突、定位模糊、当前处置决定与决策阈值（本平台不重新评估缺陷） |
| VOI 估计 | `voi_estimation` / `voi_estimation_result` | 估计批次（路线+as_of+依据快照，不可变、同键幂等重放）与每候选预期信息价值 evi、决策变化概率、可缩小范围、低价值标记 |
| 复检计划版本 | `reinspection_plan` / `reinspection_plan_item` | 预算/供应商/雨季约束下的组合选择版本（0/1 背包）；约束变化生成新版本，旧计划与约束快照保留 |

## 3. 核心规则实现

| 规则 | 实现 |
| --- | --- |
| 参数版本无法对应计算时**不得用最新值回填历史** | 版本解析要求 `valid_from ≤ as_of < valid_to` **且** `created_at ≤ as_of`（知识截止）；无对应版本 → 该段不可判定 `NO_PARAMETER_VERSION_AT_AS_OF` |
| 缺糙率/下游水位 → **扩大区间并标记限制来源** | 糙率缺失用缺省区间 [0.009, 0.035] 并标 `ROUGHNESS_MISSING`；下游水位缺失假设尾水至管顶+2m 壅水并标 `DOWNSTREAM_LEVEL_MISSING`；假设写入覆盖账本与证据 |
| **时间未对齐的峰值不得合成**同一事件 | 校准样本要求流量与上/下游水位在 10 分钟容差内同刻出现，否则拒绝（计入账本）；情景仅按事件窗口组合边界，峰值时刻仅在 `peakNote` 中透明列出 |
| 维修记录 → 新参数版本 | 仅当 `acceptance.result=ACCEPTED` 且 `measuredChanges` 含实测区间且 `measurementRef` 非空（可追溯）才派生；旧版本关闭但**保留**（不自动抹去旧测量）；未实测项继承上一版本 |
| 闸门状态冲突 | 拆分条件情景（如 `G-S3=OPEN` / `G-S3=CLOSED`）分别计算，**不取平均**；CLOSED → 该段能力为 0 |
| 外部缺陷/覆盖/维修 | 只读引用接入，作证据上下文，**不重新定位缺陷、不裁决观测、不推导覆盖与维修状态**，不生成维修优先级 |
| 历史复算 | 同一 `(scenario, method, as_of)` 幂等：重复提交返回既有运行（`replayed=true`），结果逐位一致 |
| 迟到监测补录 | 观测可见性按 `ingested_at ≤ as_of` 判定：旧 as_of 复算看不到迟到数据，新 as_of 才纳入 |

## 4. 两条评估路线（相互独立，均产出可复算区间）

| | 路线A `INTERVAL_PROPAGATION` | 路线B `CALIBRATED_ENSEMBLE` |
| --- | --- | --- |
| 方法 | 工程水力公式逐段传播管径/糙率/坡度/有效断面上下界（圆管满流曼宁公式 Q=C·D_eff^(8/3)·√S_eff/n） | 经已知工况校准的水力情景集合：由同刻流量+水位反算糙率区间，多段联合传播流量与水位可行范围 |
| 耦合 | 逐段独立（本段下游节点水位） | 下游边界→上游逐段回水传播（回水及边界联动） |
| 优点 | 稳定、易逐项复核 | 能表达回水与边界联动、区间更贴合实测 |
| 代价 | 保守参数会放大区间，可能无法区分相近瓶颈 | 需要足量校准事件（<3 样本标 `CALIBRATION_INSUFFICIENT`），参数校准变化影响历史结果 |
| 选择依据 | 现场参数不确定度低、网络耦合弱、需逐项复核 | 校准事件充足、网络耦合强、需边界联动精度 |

两路线**互不作为对方的预处理或核验**，仅共享纯数学核心（曼宁公式与区间传播）。
同管段同边界条件下两路线结果可对照（见 §7 验证）。

## 5. 复检信息价值（VOI）估计

年度复检预算有限，目标是优先安排“**做一次检测最可能改变整治决定**”的位置，而非只按当前风险或巡检间隔排序。
缺陷状况、检查覆盖与维修作用范围仍来自外部评估系统（`external_ref` 只读引用）；本能力只保存引用快照并估计复检价值。

### 5.1 两条估计路线（同一 evi 口径，相互独立）

| | 路线甲 `ENUMERATION` 结果枚举法 | 路线乙 `SENSITIVITY` 局部敏感度法 |
| --- | --- | --- |
| 方法 | 枚举“发现更严重 / 确认现状 / 发现已修复”三个离散结果：先验风险分布按业务阈值带（MONITOR 40 / REMEDIATE 70）分区，取截断正态条件期望求各结果测得风险，贝叶斯共轭得后验决定，按结果边际概率加权 | 在观测不确定度上局部线性化：先验经复检测量收缩为后验，仅在当前决定带边界 ±1.5σ 局部窗口内计算阈值穿越概率，近似预期决策变化 |
| 业务阈值 | 直接套用阈值带，可表达离散阈值跳变 | 局部窗口内近似；窗口外跳变在 `mayMissDiscreteJumps` 显式标注 |
| 优点 | 直接反映业务阈值、结果可逐项复核 | 每候选常数次 CDF 计算，适合大批候选筛选 |
| 代价 | 多点组合结果空间组合增长 | 可能忽略离散阈值跳变，不表达“发现已修复”等离散结果 |
| 选择依据 | 候选规模小（默认 ≤12）、结果分布离散（证据冲突/待验收维修/近阈值）、需组合优化复核 | 候选规模大；离散场景可再单独跑枚举法对照 |

两路线**度量同一复检对处置结论的预期信息价值 evi（[0,1]）**，按相同的决策/剩余风险长度/网络影响三权重与同一分辨率降权合成；
**敏感度法不作为枚举法的预处理或预热**，`AUTO` 仅依据候选规模与离散性选路，理由写入估计批次 `basisJson`。

### 5.2 关键业务规则

| 规则 | 实现 |
| --- | --- |
| 低价值检查明确标记 | 所有结果都无法改变决定（`DECISION_IMMUTABLE`）、决策变化概率 < 0.05、或分辨率严重不足 → `lowValue=true` 并给出原因 |
| 已维修且完整验收不重复推荐 | `REPAIR_ACTION` 含 `ACCEPTED`+`acceptedAt`+可追溯 `measurementRef` 且维修范围与候选位置重叠 → `excludedRepaired=true`，evi=0；已验收但不可追溯不构成完整覆盖 |
| 分辨率不足降低价值 | 方法分辨率无法区分相邻缺陷（间距取位置条目 `adjacentDefectSpacingM`）→ `resolutionPenalty<1`，evi 同比下降 |
| 维修后复检 | 待验收（PENDING）维修位置：上调“发现已修复”结果概率，枚举法可表达该离散结果（敏感度法会标注其局限） |
| 估计输出 | 每项复检对**整治排序、剩余风险长度、网络影响结论可能缩小的范围**（`riskLengthNarrowing`/`networkConclusionNarrowing`）与预期决策变化概率 |
| 不可变与幂等 | 估计批次与结果无修改/删除接口；同 `(评估版本, 路线, as_of)` 重复提交重放同一批次，evi 逐位一致 |
| 计划版本留痕 | 预算/供应商可达性/雨季禁入变化 → 同 `planCode` 下生成新版本，`supersedesPlanId` 链接前序，旧计划与约束快照保留 |
| 多点预算竞争 | 0/1 背包（容量=预算、价值=evi）求最优组合，落选原因区分预算竞争/不可达/雨季/低价值/已维修覆盖 |

## 6. API 概览

| 接口 | 说明 |
| --- | --- |
| `POST /api/network-versions` · `POST /api/network-versions/{code}/segments` | 登记管网版本与管段 |
| `POST /api/segments/{id}/parameter-versions` · `GET .../parameter-versions?at=` | 登记/查询参数版本（`at` 解析历史时点，不回填） |
| `POST /api/observations/batch` · `GET /api/observations` | 观测入库（迟到补录用旧 `sampledAt` 即可）与查询 |
| `POST /api/events` | 登记水文事件窗口 |
| `POST /api/external-refs` · `GET /api/external-refs` | 登记/查询外部只读引用（无修改/删除接口） |
| `POST /api/external-refs/{id}/derive-parameter-version` | 维修验收派生新参数版本（无验收/不可追溯 → 422） |
| `POST /api/scenarios/assemble` · `GET /api/scenarios` | 组装情景（闸门冲突自动拆分条件情景） |
| `POST /api/computations` | 执行评估：`{scenarioId, method, asOf?}`，同键幂等 |
| `GET /api/computations/{runId}` · `/coverage` · `/evidence` | 运行结果、覆盖账本、证据回查 |
| `GET /api/scenarios/{id}/capacity?method=` | **网络情景查询**：各次运行的网络区间与瓶颈排序（下游系统接口） |
| `POST /api/reinspection/candidates` · `GET /api/reinspection/candidates[/{id}]` | 登记/查询复检候选方案（覆盖位置/分辨率/定位误差/成本/可发现类别/供应商/雨季） |
| `POST /api/reinspection/assessments` · `GET .../assessments[/{id}]` · `GET .../assessments/{id}/entries` | 登记/查询外部状况评估版本（只读引用快照、阈值、各位置风险区间/证据冲突） |
| `POST /api/reinspection/estimations` · `GET .../estimations/{id}[/results]` | 执行/查询 VOI 估计（`method=ENUMERATION/SENSITIVITY/AUTO`，同键幂等；结果含 evi 排名、决策变化、可缩小范围、低价值标记） |
| `POST /api/reinspection/plans` · `GET .../plans` · `.../plans/by-code/{code}` · `.../plans/{id}/items` | 生成/查询复检计划版本（约束变化出新版本，旧版本与明细原因保留） |

## 7. 验证方式（以下均为实际执行过的验证）

### 7.1 自动化测试

```bash
mvn test
# Tests run: 30, Failures: 0, Errors: 0 — BUILD SUCCESS
```

覆盖（原有 22 项）：曼宁公式数值、有效坡度/回水、区间变宽单调性、贡献度归一；版本窗口解析与知识截止；
糙率/下游水位缺失扩大与标记；闸门关闭置零与冲突拆分；未对齐样本拒绝；幂等重放；
迟到补录可见性；历史复算版本选择；覆盖账本记录。

新增复检信息价值 8 项（`ReinspectionVoiTest`）：近阈值位置价值高于高风险证据充分位置、预期决策变化概率、
低价值（决定不可变）标记、完整验收维修覆盖排除、分辨率不足降权、两路线同口径 evi 与离散局限标注、
AUTO 按候选规模选路、预算竞争与计划版本留痕、估计批次幂等重放。

### 7.2 种子数据验收场景（启动后自动写入）

主干线 `N1→S1→N2→S2→N3→S3→N4→S4→B1`（管网版本 `NV-2024A`）：

- **S2 断面局部缩减**：有效直径区间 [900, 960]mm（名义 1000mm），缺陷引用 D-1001 佐证
- **S3 淤积测量区间**：淤积率 [0.10, 0.20]
- **S4 糙率缺失**：内衬资料不全，糙率区间为空
- **E-2026-0714-STORM**：完整降雨/水位/流量监测；闸门 G-S3 在窗口内有 OPEN 与 CLOSED 冲突观测
- **E-2026-0802-STORM**：仅降雨，无水位观测（演示下游水位缺失）
- **维修记录**：R-3001（有验收+可追溯实测）、R-3002（无验收）、R-3003（缺 measurementRef）
- **复检 VOI 五类样例**：S1 高风险证据充分、S3 低风险近阈值（兼 R-3002 待验收维修）、S4 定位模糊（相邻缺陷 20mm）、
  S2 维修后复检（R-3001 完整验收覆盖区 + 维修范围外候选）、7 候选多点预算竞争（外部评估版本 `CA-2026.11`）

### 7.3 能力评估验收执行记录（2026-11-20 在隔离容器内实际执行）

```bash
# 1) 闸门冲突 → 拆分为 2 个条件情景（不取平均）
POST /api/scenarios/assemble  →  E-2026-0714-STORM|S1-S2-S3-S4|G-S3=OPEN
                                 E-2026-0714-STORM|S1-S2-S3-S4|G-S3=CLOSED

# 2) 路线A（as_of=2026-07-20，OPEN）：能力区间/余量/限制/瓶颈
S1 q=[533.3, 1190.3] 余量=[-0.407, 0.323]
S2 q=[0.0, 996.9]   余量=[-1.000, 0.108]  限制=[BACKWATER_DOMINATED]   ← 瓶颈
S3 q=[769.0, 1552.5] 余量=[-0.451, 0.109]
S4 q=[255.8, 2580.7] 余量=[-0.817, 0.843] 限制=[ROUGHNESS_MISSING]     ← 糙率缺失放大区间
网络区间=[0.0, 996.9] L/s；重复提交同键 → replayed=true（幂等）

# 3) 路线B（同情景同边界）：校准+联合传播
S1 q=[0.0, 1098.8] 校准样本=3 拒绝=0  水位可行范围=[12.61, 13.85]
S2 q=[0.0, 934.9]  校准样本=3 拒绝=1（03:25 未对齐流量被拒绝）
S3 q=[249.8, 1484.5]；S4 q=[639.7, 1721.2]（校准糙率替代缺省区间，区间收窄）

# 4) 闸门 CLOSED 条件情景：S3 q=[0.0, 0.0] 限制=[GATE_CLOSED]，瓶颈=S3

# 5) E2 暴雨（无水位观测）：四段均标 [DOWNSTREAM_LEVEL_MISSING]，区间扩大

# 6) 维修派生：R-3002 → 422（无验收）；R-3003 → 422（缺 measurementRef）；
#    R-3001 → 201 生成 S2 v2（直径 [968,985]，糙率 [0.011,0.013]，validFrom=2026-09-01）；
#    v1 关闭但保留（valid=[2026-01-01 → 2026-09-01]）

# 7) 历史复算：
#    as_of=2026-07-20 → 重放 run 1，S2 仍为 v1 值 [0.0, 996.9]
#    as_of=2026-10-01 → v1 已关闭、v2 尚未登记（知识空窗）→ S2 不可判定 NO_PARAMETER_VERSION_AT_AS_OF（不回填）
#    as_of=现在       → S2 用 v2：q=[421.8, 1247.2]（维修后能力提升）

# 8) 迟到补录：补录 B1 水位 12.90m（采样 07-14 05:30）→
#    旧 as_of 复算：replayed=true，S4 不变 [255.8, 2580.7]（补录不可见）
#    新 as_of：S4 尾水=[11.3, 12.9]（含补录 obs#35），q=[0.0, 2580.7]（回水主导）

# 9) 覆盖账本/证据回查：S4 主导不确定参数 ROUGHNESS 73.9%，
#    假设“糙率缺失采用缺省区间 [0.009,0.035]”写入证据；传播尾水与观测尾水分别标注

# 10) 网络情景查询 GET /api/scenarios/1/capacity：
#    随 as_of 演变——as_of=07-20 瓶颈 S2；as_of=10-01 S2 不可判定；维修后瓶颈 S1
```

### 7.4 复检信息价值验收执行记录（2026-11-20 在隔离容器内实际执行）

种子自动写入外部评估版本 `CA-2026.11`（阈值 MONITOR=40 / REMEDIATE=70）、7 个候选与估计批次 1（枚举法）、批次 2（敏感度法）、计划 `PLAN-ANNUAL-2026` v1。

```bash
# 1) AUTO 选路：7 候选（≤12）且存在证据冲突/待验收维修/近阈值 → ENUMERATION（理由写入 basisJson）
POST /api/reinspection/estimations {"assessmentId":1,"method":"AUTO"}  → selectedMethod=ENUMERATION

# 2) 枚举法 evi 排名（五类样例）
rank cand seg  evi    决策变化概率  低价值  维修排除  分辨率降权   说明
 1    5   S3  0.339    1.000       否      否       1.00   低风险近阈值（风险中点39贴近阈值40），最可能改变决定
 2    4   S3  0.195    0.655       否      否       1.00   同位置另一方法，待验收维修上调修复结果概率
 3    7   S4  0.031    0.212       否      否       1.00   定位模糊（歧义8m、证据冲突），高精度 CCTV 仍有价值
 4    3   S2  0.001    0.000       是      否       1.00   DECISION_IMMUTABLE
 5    1   S1  0.000    0.000       是      否       1.00   高风险但证据充分，任何结果都维持 REMEDIATE
 6    2   S2  0.000    0.000       是      是       1.00   R-3001 完整验收覆盖（ACCEPTED+可追溯），不重复推荐
 7    6   S4  0.000    0.000       是      否       0.50   SONAR 20mm 分辨率无法区分 20mm 间距相邻缺陷，降权
# 结论：近阈值 S3 价值 > 定位模糊 S4 > 高风险证据充分 S1，与“按最可能改变整治决定排序”一致

# 3) 敏感度法（同口径 evi∈[0,1]，独立产出）：近阈值 S4→候选4 evi=0.110、定位模糊候选7 evi=0.096 居前；
#    每个非排除结果含 boundaryCrossings 与 mayMissDiscreteJumps 标注（离散跳变局限），维修覆盖候选同样排除

# 4) 枚举结果分解（候选4，S3 近阈值，先验 μ=39 σ=2.5）：
#    CONFIRM_CURRENT p=0.345 后验 40.03 → MONITOR（维持）；FIND_REPAIRED p=0.655 后验 27.85 → NO_ACTION（降级）；
#    FIND_WORSE p≈0（目标带无概率）→ 决策变化概率=0.655；输出剩余风险长度与网络结论可缩小范围

# 5) 计划 v1（预算3万 + 仅可达供应商 + 雨季窗口11月）：
#    入选 候选4(9000)+候选7(16000)=25000，总 evi=0.226；
#    落选：候选5 RAINY_SEASON_BLOCKED、候选6 SUPPLIER_UNREACHABLE、候选1/3 LOW_VALUE、候选2 REPAIRED_ACCEPTED

# 6) 约束变化 → 计划 v2（预算1.2万、允许不可达、取消雨季），supersedesPlanId 指向 v1：
#    入选 候选5(5000, evi=0.339)；候选4/7 因 BUDGET_COMPETITION 落选；总成本 5000 ≤ 预算
# GET /plans/by-code/PLAN-ANNUAL-2026 → v1(预算3万,雨季窗口) 与 v2(预算1.2万,无雨季) 均保留，约束快照可回查

# 7) 权限：无凭据 → 401；viewer GET → 200、viewer POST → 403；operator 可读写
```

## 8. 设计说明

- **as_of 语义**：复算使用“该时点的知识状态”——参数版本需有效窗覆盖且已登记，观测需已入库。
  已完成的运行不可变；知识库更新（如维修版本登记）后，选择新的 as_of 计算即可对照。
- **区间语义**：上界取有利端（大断面、小淤积、陡坡、小糙率、低尾水），下界取相反端；
  尾水超过管顶按出口淹没取水头差/管长为有效坡度，下界可为 0（标 `BACKWATER_DOMINATED`）。
- **缺省假设**（`application.yml` 可调，均写入账本与证据）：糙率 [0.009, 0.035]、淤积上限 0.30、
  出口壅水 2.0m、校准对齐容差 600s、校准样本下限 3 条。
- **VOI 语义**：风险区间按 ±2σ 解释为截断正态先验；复检测量不确定度由方法分辨率/定位误差/定位模糊决定；
  evi 为处置决定变化（0.60）、剩余风险长度缩小（0.25）、网络影响结论缩小（0.15）三分量按分辨率降权后的加权，
  参数集中在 `app.voi.*`，两路线共用，保证同口径可对照。
- **不做的事**：不重新定位/评估缺陷、不裁决外部观测、不推导覆盖与维修状态（只读引用外部系统）、不生成维修整治优先级；
  VOI 两路线互不预处理/核验（敏感度法不给枚举法预热）；不用最新参数回填历史；不平均冲突闸门状态；不合成未对齐峰值；
  估计结果与计划版本不可变、无删除接口。
- **容器内验证说明**：本隔离容器未安装 Docker 守护进程，`docker compose` 未实际运行；
  已通过 `mvn test`（30 项全绿）与 `mvn spring-boot:run` 启动后对真实 HTTP 接口完成端到端验收，
  Dockerfile/docker-compose 为标准 Spring Boot + PostgreSQL 18 配置，可在具备 Docker 的环境一键启动。
