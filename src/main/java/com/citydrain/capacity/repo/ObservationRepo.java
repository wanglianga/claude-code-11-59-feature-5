package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.Observation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class ObservationRepo {

    public static final RowMapper<Observation> MAPPER = (rs, i) -> new Observation(
            rs.getLong("id"),
            rs.getString("obs_type"),
            rs.getString("target_type"),
            rs.getString("target_code"),
            (Double) rs.getObject("value_num"),
            rs.getString("value_text"),
            rs.getString("unit"),
            rs.getObject("sampled_at", OffsetDateTime.class),
            rs.getObject("ingested_at", OffsetDateTime.class),
            rs.getString("source"),
            rs.getString("source_version"),
            rs.getString("calibration_status")
    );

    private final JdbcClient db;

    public ObservationRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insert(Observation o) {
        // ingested_at 允许显式指定（种子数据模拟历史入库）；为空取当前时间
        return db.sql("""
                INSERT INTO observation(obs_type, target_type, target_code, value_num, value_text,
                                        unit, sampled_at, ingested_at, source, source_version, calibration_status)
                VALUES (?,?,?,?,?,?,?,COALESCE(?, now()),?,?,?) RETURNING id
                """)
                .params(o.obsType(), o.targetType(), o.targetCode(), o.valueNum(), o.valueText(),
                        o.unit(), o.sampledAt(), o.ingestedAt(), o.source(), o.sourceVersion(),
                        o.calibrationStatus())
                .query(Long.class)
                .single();
    }

    public List<Observation> query(String obsType, String targetCode,
                                   OffsetDateTime from, OffsetDateTime to, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM observation WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (obsType != null) { sql.append(" AND obs_type = ?"); args.add(obsType); }
        if (targetCode != null) { sql.append(" AND target_code = ?"); args.add(targetCode); }
        if (from != null) { sql.append(" AND sampled_at >= ?"); args.add(from); }
        if (to != null) { sql.append(" AND sampled_at <= ?"); args.add(to); }
        sql.append(" ORDER BY sampled_at LIMIT ?");
        args.add(limit);
        return db.sql(sql.toString()).params(args.toArray()).query(MAPPER).list();
    }

    /**
     * 计算用查询：事件窗口内、且在 as_of 前已入库（迟到补录对历史复算不可见）。
     */
    public List<Observation> forComputation(String obsType, String targetCode,
                                            OffsetDateTime windowStart, OffsetDateTime windowEnd,
                                            OffsetDateTime asOf) {
        return db.sql("""
                SELECT * FROM observation
                WHERE obs_type = ? AND target_code = ?
                  AND sampled_at >= ? AND sampled_at <= ?
                  AND ingested_at <= ?
                ORDER BY sampled_at
                """)
                .params(obsType, targetCode, windowStart, windowEnd, asOf)
                .query(MAPPER).list();
    }

    /** 校准用：某目标在 asOf 前入库的全部数值型观测（不限窗口）。 */
    public List<Observation> forCalibration(String obsType, String targetCode, OffsetDateTime asOf) {
        return db.sql("""
                SELECT * FROM observation
                WHERE obs_type = ? AND target_code = ? AND ingested_at <= ? AND value_num IS NOT NULL
                ORDER BY sampled_at
                """)
                .params(obsType, targetCode, asOf)
                .query(MAPPER).list();
    }

    /** 在目标时刻附近 toleranceSec 内找最近一条（asOf 前入库）。 */
    public java.util.Optional<Observation> nearest(String obsType, String targetCode,
                                                   OffsetDateTime at, long toleranceSec,
                                                   OffsetDateTime asOf) {
        return db.sql("""
                SELECT * FROM observation
                WHERE obs_type = ? AND target_code = ? AND ingested_at <= ?
                  AND sampled_at BETWEEN ? AND ?
                ORDER BY abs(extract(epoch from (sampled_at - ?)))
                LIMIT 1
                """)
                .params(obsType, targetCode, asOf,
                        at.minusSeconds(toleranceSec), at.plusSeconds(toleranceSec), at)
                .query(MAPPER).optional();
    }
}
