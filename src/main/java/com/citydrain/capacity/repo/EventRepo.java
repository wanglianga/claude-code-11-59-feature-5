package com.citydrain.capacity.repo;

import com.citydrain.capacity.domain.HydroEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class EventRepo {

    public static final RowMapper<HydroEvent> MAPPER = (rs, i) -> new HydroEvent(
            rs.getLong("id"),
            rs.getString("event_code"),
            rs.getString("name"),
            rs.getObject("window_start", OffsetDateTime.class),
            rs.getObject("window_end", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    private final JdbcClient db;

    public EventRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insert(String eventCode, String name, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        return db.sql("INSERT INTO hydro_event(event_code, name, window_start, window_end) VALUES (?,?,?,?) RETURNING id")
                .params(eventCode, name, windowStart, windowEnd)
                .query(Long.class).single();
    }

    public Optional<HydroEvent> findById(long id) {
        return db.sql("SELECT * FROM hydro_event WHERE id = ?").params(id).query(MAPPER).optional();
    }

    public List<HydroEvent> list() {
        return db.sql("SELECT * FROM hydro_event ORDER BY window_start DESC").query(MAPPER).list();
    }
}
