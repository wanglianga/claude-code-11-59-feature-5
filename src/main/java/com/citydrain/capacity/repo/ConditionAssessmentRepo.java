package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.ConditionAssessmentVersion;
import com.citydrain.capacity.domain.ConditionPositionEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 状况评估版本（外部评估系统只读引用快照）与位置条目仓储。 */
@Repository
public class ConditionAssessmentRepo {

    public static final RowMapper<ConditionAssessmentVersion> VERSION_MAPPER = (rs, i) ->
            new ConditionAssessmentVersion(
                    rs.getLong("id"),
                    rs.getString("assessment_code"),
                    rs.getString("source_system"),
                    rs.getString("source_version"),
                    rs.getString("external_ref_ids"),
                    rs.getLong("network_version_id"),
                    rs.getString("decision_thresholds"),
                    rs.getString("payload"),
                    rs.getObject("registered_at", java.time.OffsetDateTime.class)
            );

    public static final RowMapper<ConditionPositionEntry> ENTRY_MAPPER = (rs, i) ->
            new ConditionPositionEntry(
                    rs.getLong("id"),
                    rs.getLong("assessment_id"),
                    rs.getLong("segment_id"),
                    rs.getString("location_range"),
                    rs.getDouble("risk_score_min"),
                    rs.getDouble("risk_score_max"),
                    rs.getBoolean("evidence_conflict"),
                    rs.getDouble("location_ambiguity_m"),
                    rs.getString("current_decision"),
                    rs.getDouble("current_risk_length_m"),
                    rs.getString("note")
            );

    private final JdbcClient db;

    public ConditionAssessmentRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insertVersion(ConditionAssessmentVersion v) {
        return db.sql("""
                INSERT INTO condition_assessment_version(assessment_code, source_system, source_version,
                    external_ref_ids, network_version_id, decision_thresholds, payload)
                VALUES (?,?,?,?,?,?,?) RETURNING id
                """)
                .params(v.assessmentCode(), v.sourceSystem(), v.sourceVersion(),
                        Jsonb.of(v.externalRefIdsJson()), v.networkVersionId(),
                        Jsonb.of(v.decisionThresholdsJson()), Jsonb.of(v.payloadJson()))
                .query(Long.class).single();
    }

    public void insertEntry(ConditionPositionEntry e) {
        db.sql("""
                INSERT INTO condition_position_entry(assessment_id, segment_id, location_range,
                    risk_score_min, risk_score_max, evidence_conflict, location_ambiguity_m,
                    current_decision, current_risk_length_m, note)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """)
                .params(e.assessmentId(), e.segmentId(), Jsonb.of(e.locationRangeJson()),
                        e.riskScoreMin(), e.riskScoreMax(), e.evidenceConflict(),
                        e.locationAmbiguityM(), e.currentDecision(), e.currentRiskLengthM(), e.note())
                .update();
    }

    public Optional<ConditionAssessmentVersion> findVersionById(long id) {
        return db.sql("SELECT * FROM condition_assessment_version WHERE id = ?").params(id)
                .query(VERSION_MAPPER).optional();
    }

    public List<ConditionAssessmentVersion> listVersions() {
        return db.sql("SELECT * FROM condition_assessment_version ORDER BY id")
                .query(VERSION_MAPPER).list();
    }

    public List<ConditionPositionEntry> listEntries(long assessmentId) {
        return db.sql("SELECT * FROM condition_position_entry WHERE assessment_id = ? ORDER BY id")
                .params(assessmentId).query(ENTRY_MAPPER).list();
    }

    public Optional<ConditionPositionEntry> findEntry(long assessmentId, long segmentId) {
        return db.sql("SELECT * FROM condition_position_entry WHERE assessment_id = ? AND segment_id = ?")
                .params(assessmentId, segmentId).query(ENTRY_MAPPER).optional();
    }
}
