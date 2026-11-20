package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.Scenario;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ScenarioRepo {

    private static final ObjectMapper MAPPER_JSON = new ObjectMapper();

    public static final RowMapper<Scenario> MAPPER = (rs, i) -> new Scenario(
            rs.getLong("id"),
            rs.getString("scenario_code"),
            rs.getLong("event_id"),
            rs.getLong("network_version_id"),
            readLongList(rs.getString("path_segment_ids")),
            rs.getString("boundary_spec"),
            rs.getString("condition_label"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    private static List<Long> readLongList(String json) {
        try {
            return MAPPER_JSON.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("情景路径 JSON 解析失败", e);
        }
    }

    private final JdbcClient db;

    public ScenarioRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insert(String scenarioCode, long eventId, long networkVersionId,
                       String pathSegmentIdsJson, String boundarySpecJson, String conditionLabel) {
        return db.sql("""
                INSERT INTO scenario(scenario_code, event_id, network_version_id,
                                     path_segment_ids, boundary_spec, condition_label)
                VALUES (?,?,?,?,?,?) RETURNING id
                """)
                .params(scenarioCode, eventId, networkVersionId,
                        Jsonb.of(pathSegmentIdsJson), Jsonb.of(boundarySpecJson), conditionLabel)
                .query(Long.class).single();
    }

    public Optional<Scenario> findById(long id) {
        return db.sql("SELECT * FROM scenario WHERE id = ?").params(id).query(MAPPER).optional();
    }

    public Optional<Scenario> findByCode(String scenarioCode) {
        return db.sql("SELECT * FROM scenario WHERE scenario_code = ?").params(scenarioCode).query(MAPPER).optional();
    }

    public List<Scenario> list() {
        return db.sql("SELECT * FROM scenario ORDER BY id").query(MAPPER).list();
    }
}
