package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.CapacityResult;
import com.citydrain.capacity.domain.ComputationRun;
import com.citydrain.capacity.domain.CoverageEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class RunRepo {

    public static final RowMapper<ComputationRun> RUN_MAPPER = (rs, i) -> new ComputationRun(
            rs.getLong("id"),
            rs.getString("run_code"),
            rs.getLong("scenario_id"),
            rs.getString("method"),
            rs.getObject("as_of", OffsetDateTime.class),
            rs.getString("status"),
            rs.getString("network_summary"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    public static final RowMapper<CapacityResult> RESULT_MAPPER = (rs, i) -> new CapacityResult(
            rs.getLong("id"),
            rs.getLong("run_id"),
            rs.getLong("segment_id"),
            (Double) rs.getObject("q_min_lps"),
            (Double) rs.getObject("q_max_lps"),
            (Double) rs.getObject("margin_min"),
            (Double) rs.getObject("margin_max"),
            rs.getString("dominant_uncertainty"),
            rs.getString("limitations"),
            rs.getString("undeterminable_reason"),
            rs.getString("evidence")
    );

    public static final RowMapper<CoverageEntry> LEDGER_MAPPER = (rs, i) -> new CoverageEntry(
            rs.getLong("id"),
            rs.getLong("run_id"),
            (Long) rs.getObject("segment_id"),
            rs.getString("item"),
            rs.getString("status"),
            rs.getString("detail")
    );

    private final JdbcClient db;

    public RunRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insertRun(String runCode, long scenarioId, String method, OffsetDateTime asOf) {
        return db.sql("INSERT INTO computation_run(run_code, scenario_id, method, as_of) VALUES (?,?,?,?) RETURNING id")
                .params(runCode, scenarioId, method, asOf)
                .query(Long.class).single();
    }

    public void finishRun(long runId, String networkSummaryJson) {
        db.sql("UPDATE computation_run SET status = 'COMPLETED', network_summary = ? WHERE id = ?")
                .params(Jsonb.of(networkSummaryJson), runId).update();
    }

    public Optional<ComputationRun> findRun(long scenarioId, String method, OffsetDateTime asOf) {
        return db.sql("SELECT * FROM computation_run WHERE scenario_id = ? AND method = ? AND as_of = ?")
                .params(scenarioId, method, asOf).query(RUN_MAPPER).optional();
    }

    public Optional<ComputationRun> findRunById(long runId) {
        return db.sql("SELECT * FROM computation_run WHERE id = ?").params(runId).query(RUN_MAPPER).optional();
    }

    public List<ComputationRun> listRunsByScenario(long scenarioId) {
        return db.sql("SELECT * FROM computation_run WHERE scenario_id = ? ORDER BY id")
                .params(scenarioId).query(RUN_MAPPER).list();
    }

    public void insertResult(long runId, long segmentId, Double qMinLps, Double qMaxLps,
                             Double marginMin, Double marginMax,
                             String dominantUncertaintyJson, String limitationsJson,
                             String undeterminableReason, String evidenceJson) {
        db.sql("""
                INSERT INTO capacity_result(run_id, segment_id, q_min_lps, q_max_lps,
                                            margin_min, margin_max, dominant_uncertainty,
                                            limitations, undeterminable_reason, evidence)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """)
                .params(runId, segmentId, qMinLps, qMaxLps, marginMin, marginMax,
                        Jsonb.of(dominantUncertaintyJson), Jsonb.of(limitationsJson),
                        undeterminableReason, Jsonb.of(evidenceJson))
                .update();
    }

    public List<CapacityResult> listResults(long runId) {
        return db.sql("SELECT * FROM capacity_result WHERE run_id = ? ORDER BY segment_id")
                .params(runId).query(RESULT_MAPPER).list();
    }

    public void insertLedger(long runId, Long segmentId, String item, String status, String detail) {
        db.sql("INSERT INTO coverage_ledger(run_id, segment_id, item, status, detail) VALUES (?,?,?,?,?)")
                .params(runId, segmentId, item, status, detail).update();
    }

    public List<CoverageEntry> listLedger(long runId) {
        return db.sql("SELECT * FROM coverage_ledger WHERE run_id = ? ORDER BY id")
                .params(runId).query(LEDGER_MAPPER).list();
    }
}
