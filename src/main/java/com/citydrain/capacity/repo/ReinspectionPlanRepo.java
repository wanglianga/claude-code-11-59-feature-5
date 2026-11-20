package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.ReinspectionPlan;
import com.citydrain.capacity.domain.ReinspectionPlanItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 复检计划版本与明细仓储：计划不可变（无更新/删除），约束变化生成新版本。 */
@Repository
public class ReinspectionPlanRepo {

    public static final RowMapper<ReinspectionPlan> PLAN_MAPPER = (rs, i) -> new ReinspectionPlan(
            rs.getLong("id"),
            rs.getString("plan_code"),
            rs.getInt("plan_version"),
            rs.getLong("estimation_id"),
            rs.getDouble("budget"),
            rs.getBoolean("supplier_only_reachable"),
            rs.getString("rainy_season_window"),
            rs.getDouble("total_cost"),
            rs.getDouble("total_evi"),
            rs.getString("constraint_snapshot"),
            (Long) rs.getObject("supersedes_plan_id"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    public static final RowMapper<ReinspectionPlanItem> ITEM_MAPPER = (rs, i) -> new ReinspectionPlanItem(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getLong("candidate_id"),
            rs.getLong("segment_id"),
            rs.getBoolean("selected"),
            rs.getDouble("evi"),
            rs.getDouble("cost"),
            rs.getDouble("expected_risk_reduction_m"),
            rs.getDouble("decision_change_prob"),
            rs.getString("reason"),
            rs.getString("detail")
    );

    private final JdbcClient db;

    public ReinspectionPlanRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public int nextVersionNo(String planCode) {
        Integer max = db.sql("SELECT max(plan_version) FROM reinspection_plan WHERE plan_code = ?")
                .params(planCode).query(Integer.class).optional().orElse(null);
        return max == null ? 1 : max + 1;
    }

    public long insertPlan(ReinspectionPlan p) {
        return db.sql("""
                INSERT INTO reinspection_plan(plan_code, plan_version, estimation_id, budget,
                    supplier_only_reachable, rainy_season_window, total_cost, total_evi,
                    constraint_snapshot, supersedes_plan_id)
                VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id
                """)
                .params(p.planCode(), p.planVersion(), p.estimationId(), p.budget(),
                        p.supplierOnlyReachable(),
                        p.rainySeasonWindowJson() == null ? null : Jsonb.of(p.rainySeasonWindowJson()),
                        p.totalCost(), p.totalEvi(), Jsonb.of(p.constraintSnapshotJson()),
                        p.supersedesPlanId())
                .query(Long.class).single();
    }

    public void insertItem(ReinspectionPlanItem item) {
        db.sql("""
                INSERT INTO reinspection_plan_item(plan_id, candidate_id, segment_id, selected, evi,
                    cost, expected_risk_reduction_m, decision_change_prob, reason, detail)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """)
                .params(item.planId(), item.candidateId(), item.segmentId(), item.selected(),
                        item.evi(), item.cost(), item.expectedRiskReductionM(),
                        item.decisionChangeProb(), item.reason(), item.detail())
                .update();
    }

    public Optional<ReinspectionPlan> findById(long id) {
        return db.sql("SELECT * FROM reinspection_plan WHERE id = ?").params(id)
                .query(PLAN_MAPPER).optional();
    }

    public List<ReinspectionPlan> listByPlanCode(String planCode) {
        return db.sql("SELECT * FROM reinspection_plan WHERE plan_code = ? ORDER BY plan_version")
                .params(planCode).query(PLAN_MAPPER).list();
    }

    public List<ReinspectionPlan> listAll() {
        return db.sql("SELECT * FROM reinspection_plan ORDER BY id").query(PLAN_MAPPER).list();
    }

    public List<ReinspectionPlanItem> listItems(long planId) {
        return db.sql("SELECT * FROM reinspection_plan_item WHERE plan_id = ? ORDER BY id")
                .params(planId).query(ITEM_MAPPER).list();
    }
}
