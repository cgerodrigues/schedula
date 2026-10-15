package io.github.cgerodrigues.schedula;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationCompatibilityTest extends IntegrationBase {
  @Test
  void upgradingV2PreservesExistingCredentialsAndAffiliations() throws Exception {
    db.jdbc.execute("create database migration_compatibility");
    String url =
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/migration_compatibility";
    Flyway.configure()
        .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
        .target("2")
        .load()
        .migrate();
    java.util.UUID staff = java.util.UUID.randomUUID(),
        city = java.util.UUID.randomUUID(),
        unit = java.util.UUID.randomUUID(),
        link = java.util.UUID.randomUUID();
    try (var connection =
        java.sql.DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      try (var statement =
          connection.prepareStatement(
              "insert into staff_user(id,username,password_hash,name,role) values"
                  + " (?,'legacy','legacy-hash','Conta existente','DEFENSOR')")) {
        statement.setObject(1, staff);
        statement.executeUpdate();
      }
      try (var statement =
          connection.prepareStatement("insert into municipality values (?,'Legado','CE')")) {
        statement.setObject(1, city);
        statement.executeUpdate();
      }
      try (var statement =
          connection.prepareStatement(
              "insert into defensoria_unit(id,municipality_id,name) values (?,?,'Unidade"
                  + " existente')")) {
        statement.setObject(1, unit);
        statement.setObject(2, city);
        statement.executeUpdate();
      }
      try (var statement =
          connection.prepareStatement("insert into affiliation values (?,?,?,'CIVEL')")) {
        statement.setObject(1, link);
        statement.setObject(2, staff);
        statement.setObject(3, unit);
        statement.executeUpdate();
      }
    }
    Flyway.configure()
        .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
    try (var connection =
            java.sql.DriverManager.getConnection(
                url, POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement();
        var result =
            statement.executeQuery(
                "select s.status,s.password_hash,a.id from staff_user s join affiliation a on"
                    + " a.defender_id=s.id")) {
      assertTrue(result.next());
      assertEquals("ACTIVE", result.getString(1));
      assertEquals("legacy-hash", result.getString(2));
      assertEquals(link, result.getObject(3));
    }
  }

  @Test
  void upgradingV3PreservesEveryExistingColumnAndBackfillsOnlyKnownAreas() throws Exception {
    var slot = slot(10);
    appointments.reserve(slot, "5585999999999", "Pessoa", "Legada");
    db.jdbc.execute("create database migration_v3_preservation");
    String url =
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/migration_v3_preservation";
    Flyway.configure()
        .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
        .target("3")
        .load()
        .migrate();
    var tables =
        java.util.List.of(
            "municipality",
            "defensoria_unit",
            "staff_user",
            "account_invitation",
            "affiliation",
            "availability_series",
            "slot",
            "schedule_block",
            "citizen",
            "appointment",
            "audit_event");
    var snapshots =
        new java.util.LinkedHashMap<String, java.util.List<java.util.Map<String, Object>>>();
    var jdbc =
        new org.springframework.jdbc.core.JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    for (String table : tables) {
      var columns =
          jdbc.queryForList(
              "select column_name from information_schema.columns where table_schema='public' and"
                  + " table_name=? order by ordinal_position",
              String.class,
              table);
      var rows = db.list("select " + String.join(",", columns) + " from " + table);
      snapshots.put(table, rows);
      for (var row : rows)
        jdbc.update(
            "insert into "
                + table
                + " ("
                + String.join(",", columns)
                + ") values ("
                + String.join(",", java.util.Collections.nCopies(columns.size(), "?"))
                + ")",
            columns.stream().map(row::get).toArray());
    }
    var legacy = java.util.UUID.randomUUID();
    jdbc.update(
        "insert into defensoria_unit(id,municipality_id,name,address,phone) values (?,?,'Unidade"
            + " sem vínculo','Endereço indivisível','telefone antigo')",
        legacy,
        municipality);
    Flyway.configure()
        .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
    for (var table : snapshots.entrySet())
      for (var row : table.getValue()) {
        var after =
            jdbc.queryForMap(
                "select "
                    + String.join(",", row.keySet())
                    + " from "
                    + table.getKey()
                    + " where id=?",
                row.get("id"));
        assertEquals(row, after, "Preservação de " + table.getKey());
      }
    assertEquals(
        2,
        jdbc.queryForObject("select count(*) from unit_area where unit_id=?", Integer.class, unit));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from unit_area where unit_id=?", Integer.class, legacy));
    assertEquals(
        0, jdbc.queryForObject("select count(*) from municipality_catalog", Integer.class));
    assertNull(
        jdbc.queryForMap("select street from defensoria_unit where id=?", legacy).get("street"));
    assertEquals(
        "Endereço indivisível",
        jdbc.queryForMap("select address from defensoria_unit where id=?", legacy).get("address"));
    assertEquals(
        "telefone antigo",
        jdbc.queryForMap("select phone from defensoria_unit where id=?", legacy).get("phone"));
  }

  @Test
  void normalizedLegacyDuplicatesStopMigrationWithoutLosingRows() {
    db.jdbc.execute("create database migration_duplicate_safety");
    String url =
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/migration_duplicate_safety";
    Flyway.configure()
        .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
        .target("3")
        .load()
        .migrate();
    var jdbc =
        new org.springframework.jdbc.core.JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.update("insert into municipality values (?,'Fortaleza','CE')", municipality);
    jdbc.update(
        "insert into defensoria_unit(id,municipality_id,name) values (?,?,' Unidade  Centro ')",
        java.util.UUID.randomUUID(),
        municipality);
    jdbc.update(
        "insert into defensoria_unit(id,municipality_id,name) values (?,?,'unidade centro')",
        java.util.UUID.randomUUID(),
        municipality);
    assertThrows(
        org.flywaydb.core.api.FlywayException.class,
        () ->
            Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate());
    assertEquals(2, jdbc.queryForObject("select count(*) from defensoria_unit", Integer.class));
    assertEquals(
        3,
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success", Integer.class));
  }
}
