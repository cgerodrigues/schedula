package io.github.cgerodrigues.schedula;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SchedulaApiApplicationTests extends IntegrationBase {
  @Test
  void contextLoads() {
    assertEquals(
        4,
        db.jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success", Integer.class));
  }
}
