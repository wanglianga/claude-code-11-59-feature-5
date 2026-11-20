package com.citydrain.capacity.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class NetworkVersionRepo {

    private final JdbcClient db;

    public NetworkVersionRepo(JdbcTemplate jdbcTemplate) {
        this.db = JdbcClient.create(jdbcTemplate);
    }

    public long insert(String code, String description) {
        return db.sql("INSERT INTO network_version(code, description) VALUES (?, ?) RETURNING id")
                .params(code, description)
                .query(Long.class)
                .single();
    }

    public Optional<Long> findIdByCode(String code) {
        return db.sql("SELECT id FROM network_version WHERE code = ?")
                .params(code)
                .query(Long.class)
                .optional();
    }

    public boolean existsById(long id) {
        return db.sql("SELECT count(*) FROM network_version WHERE id = ?")
                .params(id).query(Long.class).single() > 0;
    }

    public List<String> listCodes() {
        return db.sql("SELECT code FROM network_version ORDER BY code")
                .query(String.class)
                .list();
    }
}
