package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.ExternalRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ExternalRefRepo {

    public static final RowMapper<ExternalRef> MAPPER = (rs, i) -> new ExternalRef(
            rs.getLong("id"),
            rs.getString("ref_type"),
            rs.getString("external_id"),
            rs.getString("source_system"),
            rs.getString("source_version"),
            (Long) rs.getObject("segment_id"),
            rs.getObject("observed_at", OffsetDateTime.class),
            rs.getString("payload"),
            rs.getObject("registered_at", OffsetDateTime.class)
    );

    private final JdbcClient db;

    public ExternalRefRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insert(String refType, String externalId, String sourceSystem, String sourceVersion,
                       Long segmentId, OffsetDateTime observedAt, String payloadJson) {
        return db.sql("""
                INSERT INTO external_ref(ref_type, external_id, source_system, source_version,
                                         segment_id, observed_at, payload)
                VALUES (?,?,?,?,?,?,?) RETURNING id
                """)
                .params(refType, externalId, sourceSystem, sourceVersion, segmentId, observedAt,
                        Jsonb.of(payloadJson))
                .query(Long.class).single();
    }

    public Optional<ExternalRef> findById(long id) {
        return db.sql("SELECT * FROM external_ref WHERE id = ?").params(id).query(MAPPER).optional();
    }

    public List<ExternalRef> list(String refType, Long segmentId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM external_ref WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (refType != null) { sql.append(" AND ref_type = ?"); args.add(refType); }
        if (segmentId != null) { sql.append(" AND segment_id = ?"); args.add(segmentId); }
        sql.append(" ORDER BY id");
        return db.sql(sql.toString()).params(args.toArray()).query(MAPPER).list();
    }

    /** 只读引用：不提供 update/delete。 */
    public List<ExternalRef> listBySegmentAndType(long segmentId, String refType) {
        return db.sql("SELECT * FROM external_ref WHERE segment_id = ? AND ref_type = ? ORDER BY id")
                .params(segmentId, refType).query(MAPPER).list();
    }
}
