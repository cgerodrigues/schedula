package io.github.cgerodrigues.schedula.shared;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Database {
  public final JdbcTemplate jdbc;

  public Database(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String, Object> one(String sql, Object... args) {
    return jdbc.queryForMap(sql, args);
  }

  public List<Map<String, Object>> list(String sql, Object... args) {
    return jdbc.queryForList(sql, args);
  }

  public boolean exists(String sql, Object... args) {
    return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
  }

  public void lock(String key) {
    jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))", key);
  }

  public void audit(String actor, String action, UUID id) {
    jdbc.update(
        "insert into audit_event(actor,action,resource_id) values (?,?,?)", actor, action, id);
  }
}
