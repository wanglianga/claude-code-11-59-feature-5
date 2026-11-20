package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.ReinspectionCandidate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ReinspectionCandidateRepo {

    public static final RowMapper<ReinspectionCandidate> MAPPER = (rs, i) -> new ReinspectionCandidate(
            rs.getLong("id"),
            rs.getString("candidate_code"),
            rs.getLong("segment_id"),
            rs.getString("method_code"),
            rs.getDouble("method_resolution_mm"),
            rs.getDouble("location_error_m"),
            rs.getDouble("cost"),
            rs.getString("detectable_categories"),
            rs.getString("location_range"),
            rs.getBoolean("supplier_reachable"),
            rs.getBoolean("rainy_season_blocked"),
            rs.getString("status"),
            rs.getString("note"),
            rs.getObject("created_at", java.time.OffsetDateTime.class)
    );

    private final JdbcClient db;

    public ReinspectionCandidateRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insert(ReinspectionCandidate c) {
        return db.sql("""
                INSERT INTO reinspection_candidate(candidate_code, segment_id, method_code,
                    method_resolution_mm, location_error_m, cost, detectable_categories, location_range,
                    supplier_reachable, rainy_season_blocked, status, note)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """)
                .params(c.candidateCode(), c.segmentId(), c.methodCode(),
                        c.methodResolutionMm(), c.locationErrorM(), c.cost(),
                        Jsonb.of(c.detectableCategoriesJson()), Jsonb.of(c.locationRangeJson()),
                        c.supplierReachable(), c.rainySeasonBlocked(), c.status(), c.note())
                .query(Long.class).single();
    }

    public Optional<ReinspectionCandidate> findById(long id) {
        return db.sql("SELECT * FROM reinspection_candidate WHERE id = ?").params(id)
                .query(MAPPER).optional();
    }

    public Optional<ReinspectionCandidate> findByCode(String code) {
        return db.sql("SELECT * FROM reinspection_candidate WHERE candidate_code = ?").params(code)
                .query(MAPPER).optional();
    }

    public List<ReinspectionCandidate> listAll() {
        return db.sql("SELECT * FROM reinspection_candidate ORDER BY id").query(MAPPER).list();
    }

    public List<ReinspectionCandidate> listByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return db.sql("SELECT * FROM reinspection_candidate WHERE id = ANY(?) ORDER BY id")
                .params(java.util.Collections.singletonList(ids.toArray(new Long[0])))
                .query(MAPPER).list();
    }

    public List<ReinspectionCandidate> listBySegment(long segmentId) {
        return db.sql("SELECT * FROM reinspection_candidate WHERE segment_id = ? ORDER BY id")
                .params(segmentId).query(MAPPER).list();
    }

    /** 候选仅追加状态变更（如供应商退出 WITHDRAWN），不删除历史候选。 */
    public void updateStatus(long id, String status) {
        db.sql("UPDATE reinspection_candidate SET status = ? WHERE id = ?").params(status, id).update();
    }
}
