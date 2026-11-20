package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.VoiEstimation;
import com.citydrain.capacity.domain.VoiEstimationResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** VOI 估计批次与每候选估计结果仓储（结果不可变，无更新/删除）。 */
@Repository
public class VoiEstimationRepo {

    public static final RowMapper<VoiEstimation> EST_MAPPER = (rs, i) -> new VoiEstimation(
            rs.getLong("id"),
            rs.getString("estimation_code"),
            rs.getLong("assessment_id"),
            rs.getString("method"),
            rs.getObject("as_of", OffsetDateTime.class),
            rs.getInt("candidate_count"),
            rs.getString("basis_json"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    public static final RowMapper<VoiEstimationResult> RESULT_MAPPER = (rs, i) -> new VoiEstimationResult(
            rs.getLong("id"),
            rs.getLong("estimation_id"),
            rs.getLong("candidate_id"),
            rs.getLong("segment_id"),
            rs.getDouble("evi"),
            rs.getInt("evi_rank"),
            rs.getDouble("decision_change_prob"),
            rs.getBoolean("low_value"),
            rs.getString("low_value_reasons"),
            rs.getDouble("resolution_penalty"),
            rs.getBoolean("excluded_repaired"),
            rs.getString("outcome_breakdown"),
            rs.getString("sensitivity_detail"),
            rs.getDouble("expected_risk_reduction_m"),
            rs.getString("risk_length_narrowing"),
            rs.getString("network_conclusion_narrowing")
    );

    private final JdbcClient db;

    public VoiEstimationRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insertEstimation(String code, long assessmentId, String method, OffsetDateTime asOf,
                                 int candidateCount, String basisJson) {
        return db.sql("""
                INSERT INTO voi_estimation(estimation_code, assessment_id, method, as_of,
                                           candidate_count, basis_json)
                VALUES (?,?,?,?,?,?) RETURNING id
                """)
                .params(code, assessmentId, method, asOf, candidateCount, Jsonb.of(basisJson))
                .query(Long.class).single();
    }

    public void insertResult(VoiEstimationResult r) {
        db.sql("""
                INSERT INTO voi_estimation_result(estimation_id, candidate_id, segment_id, evi, evi_rank,
                    decision_change_prob, low_value, low_value_reasons, resolution_penalty,
                    excluded_repaired, outcome_breakdown, sensitivity_detail,
                    expected_risk_reduction_m, risk_length_narrowing, network_conclusion_narrowing)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)
                .params(r.estimationId(), r.candidateId(), r.segmentId(), r.evi(), r.eviRank(),
                        r.decisionChangeProb(), r.lowValue(), Jsonb.of(r.lowValueReasonsJson()),
                        r.resolutionPenalty(), r.excludedRepaired(),
                        r.outcomeBreakdownJson() == null ? null : Jsonb.of(r.outcomeBreakdownJson()),
                        r.sensitivityDetailJson() == null ? null : Jsonb.of(r.sensitivityDetailJson()),
                        r.expectedRiskReductionM(),
                        Jsonb.of(r.riskLengthNarrowingJson()),
                        Jsonb.of(r.networkConclusionNarrowingJson()))
                .update();
    }

    public Optional<VoiEstimation> findEstimationById(long id) {
        return db.sql("SELECT * FROM voi_estimation WHERE id = ?").params(id)
                .query(EST_MAPPER).optional();
    }

    /** 幂等键：同 (评估版本, 路线, as_of) 的估计直接重放，结果不可变。 */
    public Optional<VoiEstimation> findEstimation(long assessmentId, String method, OffsetDateTime asOf) {
        return db.sql("SELECT * FROM voi_estimation WHERE assessment_id = ? AND method = ? AND as_of = ?")
                .params(assessmentId, method, asOf).query(EST_MAPPER).optional();
    }

    public List<VoiEstimation> listEstimationsByAssessment(long assessmentId) {
        return db.sql("SELECT * FROM voi_estimation WHERE assessment_id = ? ORDER BY id")
                .params(assessmentId).query(EST_MAPPER).list();
    }

    public List<VoiEstimationResult> listResults(long estimationId) {
        return db.sql("SELECT * FROM voi_estimation_result WHERE estimation_id = ? ORDER BY evi_rank")
                .params(estimationId).query(RESULT_MAPPER).list();
    }

    public Optional<VoiEstimationResult> findResult(long estimationId, long candidateId) {
        return db.sql("SELECT * FROM voi_estimation_result WHERE estimation_id = ? AND candidate_id = ?")
                .params(estimationId, candidateId).query(RESULT_MAPPER).optional();
    }
}
