package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.ParamVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ParamVersionRepo {

    public static final RowMapper<ParamVersion> MAPPER = (rs, i) -> new ParamVersion(
            rs.getLong("id"),
            rs.getLong("segment_id"),
            rs.getInt("version_no"),
            rs.getString("source"),
            (Double) rs.getObject("diameter_min_mm"),
            (Double) rs.getObject("diameter_max_mm"),
            (Double) rs.getObject("slope_min"),
            (Double) rs.getObject("slope_max"),
            (Double) rs.getObject("roughness_min"),
            (Double) rs.getObject("roughness_max"),
            (Double) rs.getObject("silt_ratio_min"),
            (Double) rs.getObject("silt_ratio_max"),
            rs.getObject("valid_from", OffsetDateTime.class),
            rs.getObject("valid_to", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("created_by"),
            (Long) rs.getObject("repair_ref_id"),
            rs.getString("note")
    );

    private final JdbcClient db;

    public ParamVersionRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insert(ParamVersion v) {
        // created_at 允许显式指定（种子数据模拟历史登记）；为空取当前时间
        return db.sql("""
                INSERT INTO segment_parameter_version(segment_id, version_no, source,
                    diameter_min_mm, diameter_max_mm, slope_min, slope_max,
                    roughness_min, roughness_max, silt_ratio_min, silt_ratio_max,
                    valid_from, valid_to, created_at, created_by, repair_ref_id, note)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,COALESCE(?, now()),?,?,?) RETURNING id
                """)
                .params(v.segmentId(), v.versionNo(), v.source(),
                        v.diameterMinMm(), v.diameterMaxMm(), v.slopeMin(), v.slopeMax(),
                        v.roughnessMin(), v.roughnessMax(), v.siltRatioMin(), v.siltRatioMax(),
                        v.validFrom(), v.validTo(), v.createdAt(), v.createdBy(), v.repairRefId(), v.note())
                .query(Long.class)
                .single();
    }

    public int nextVersionNo(long segmentId) {
        Integer max = db.sql("SELECT max(version_no) FROM segment_parameter_version WHERE segment_id = ?")
                .params(segmentId).query(Integer.class).optional().orElse(null);
        return max == null ? 1 : max + 1;
    }

    /**
     * 解析 as_of 时点有效的参数版本：有效窗覆盖 as_of 且版本在 as_of 前已创建。
     * 无法对应时返回空——不得用最新值回填历史。
     */
    public Optional<ParamVersion> resolveAt(long segmentId, OffsetDateTime asOf) {
        return resolveValidAtCreatedBefore(segmentId, asOf, asOf);
    }

    /**
     * 双时间解析：有效窗覆盖 validAt（如观测采样时刻），且版本在 knowledgeAt 前已创建。
     * 用于校准历史工况时不以事后版本回填。
     */
    public Optional<ParamVersion> resolveValidAtCreatedBefore(long segmentId, OffsetDateTime validAt,
                                                              OffsetDateTime knowledgeAt) {
        return db.sql("""
                SELECT * FROM segment_parameter_version
                WHERE segment_id = ?
                  AND valid_from <= ?
                  AND (valid_to IS NULL OR valid_to > ?)
                  AND created_at <= ?
                ORDER BY version_no DESC
                LIMIT 1
                """)
                .params(segmentId, validAt, validAt, knowledgeAt)
                .query(MAPPER)
                .optional();
    }

    /** 当前打开的（valid_to 为 NULL）版本，用于派生新版本时关闭。 */
    public Optional<ParamVersion> findOpenVersion(long segmentId) {
        return db.sql("""
                SELECT * FROM segment_parameter_version
                WHERE segment_id = ? AND valid_to IS NULL
                ORDER BY version_no DESC LIMIT 1
                """)
                .params(segmentId).query(MAPPER).optional();
    }

    public void closeVersion(long versionId, OffsetDateTime validTo) {
        db.sql("UPDATE segment_parameter_version SET valid_to = ? WHERE id = ?")
                .params(validTo, versionId).update();
    }

    public List<ParamVersion> listBySegment(long segmentId) {
        return db.sql("SELECT * FROM segment_parameter_version WHERE segment_id = ? ORDER BY version_no")
                .params(segmentId).query(MAPPER).list();
    }

    public Optional<ParamVersion> findById(long id) {
        return db.sql("SELECT * FROM segment_parameter_version WHERE id = ?")
                .params(id).query(MAPPER).optional();
    }
}
