package com.citydrain.capacity.repo;

import org.postgresql.util.PGobject;

import java.sql.SQLException;

/** JSONB 读写辅助。 */
public final class Jsonb {

    private Jsonb() {}

    public static PGobject of(String json) {
        try {
            PGobject o = new PGobject();
            o.setType("jsonb");
            o.setValue(json);
            return o;
        } catch (SQLException e) {
            throw new IllegalArgumentException("非法 JSONB 内容", e);
        }
    }
}
