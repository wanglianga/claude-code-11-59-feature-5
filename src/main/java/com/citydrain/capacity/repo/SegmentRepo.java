package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.Segment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SegmentRepo {

    public static final RowMapper<Segment> MAPPER = (rs, i) -> new Segment(
            rs.getLong("id"),
            rs.getLong("network_version_id"),
            rs.getString("segment_code"),
            rs.getString("from_node"),
            rs.getString("to_node"),
            rs.getDouble("length_m"),
            rs.getString("shape"),
            rs.getDouble("nominal_diameter_mm"),
            rs.getDouble("upstream_invert_m"),
            rs.getDouble("downstream_invert_m"),
            rs.getDouble("design_flow_lps"),
            rs.getObject("created_at", java.time.OffsetDateTime.class)
    );

    private final JdbcClient db;

    public SegmentRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insert(long networkVersionId, String segmentCode, String fromNode, String toNode,
                       double lengthM, double nominalDiameterMm,
                       double upstreamInvertM, double downstreamInvertM, double designFlowLps) {
        return db.sql("""
                INSERT INTO pipe_segment(network_version_id, segment_code, from_node, to_node,
                                         length_m, nominal_diameter_mm, upstream_invert_m,
                                         downstream_invert_m, design_flow_lps)
                VALUES (?,?,?,?,?,?,?,?,?) RETURNING id
                """)
                .params(networkVersionId, segmentCode, fromNode, toNode, lengthM,
                        nominalDiameterMm, upstreamInvertM, downstreamInvertM, designFlowLps)
                .query(Long.class)
                .single();
    }

    public Optional<Segment> findById(long id) {
        return db.sql("SELECT * FROM pipe_segment WHERE id = ?")
                .params(id).query(MAPPER).optional();
    }

    public Optional<Segment> findByCode(long networkVersionId, String segmentCode) {
        return db.sql("SELECT * FROM pipe_segment WHERE network_version_id = ? AND segment_code = ?")
                .params(networkVersionId, segmentCode).query(MAPPER).optional();
    }

    public List<Segment> listByNetworkVersion(long networkVersionId) {
        return db.sql("SELECT * FROM pipe_segment WHERE network_version_id = ? ORDER BY segment_code")
                .params(networkVersionId).query(MAPPER).list();
    }

    public List<Segment> listByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        // 数组须作为单个参数传入（避免被 varargs 展开）
        return db.sql("SELECT * FROM pipe_segment WHERE id = ANY(?)")
                .params(java.util.Collections.singletonList(ids.toArray(new Long[0])))
                .query(MAPPER).list();
    }
}
