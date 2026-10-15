package io.github.cgerodrigues.schedula;

import io.github.cgerodrigues.schedula.appointment.AppointmentService;
import io.github.cgerodrigues.schedula.institution.InstitutionService;
import io.github.cgerodrigues.schedula.scheduling.SchedulingService;
import io.github.cgerodrigues.schedula.shared.Database;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "whatsapp.enabled=false",
      "schedula.login.max-attempts=1000",
      "whatsapp.app-secret=test-only-app-secret",
      "whatsapp.verify-token=test-only-verify-token",
      "whatsapp.phone-number-id=123456"
    })
@AutoConfigureMockMvc
@Import(IntegrationBase.Time.class)
public abstract class IntegrationBase {
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @TestConfiguration
  static class Time {
    @Bean
    @Primary
    Clock testClock() {
      return Clock.fixed(Instant.parse("2030-01-07T15:00:00Z"), ZoneId.of("America/Fortaleza"));
    }
  }

  @Autowired protected Database db;
  @Autowired protected InstitutionService institution;
  @Autowired protected SchedulingService scheduling;
  @Autowired protected AppointmentService appointments;
  @Autowired protected io.github.cgerodrigues.schedula.identity.AccountService accounts;
  @Autowired protected io.github.cgerodrigues.schedula.institution.UnitService units;
  protected UUID municipality, unit, defender, other, affiliation, criminal;

  @BeforeEach
  void fixture() {
    db.jdbc.execute(
        "truncate"
            + " notification_outbox,whatsapp_inbox,whatsapp_conversation,audit_event,appointment,citizen,slot,schedule_block,availability_series,affiliation,staff_user,defensoria_unit,municipality"
            + " cascade");
    municipality =
        institution.municipality(
            new InstitutionService.Municipality("Município Exemplo", "CE"), "test");
    db.jdbc.update("update municipality set ibge_code='2304400' where id=?", municipality);
    db.jdbc.update("delete from municipality_catalog");
    unit = units.save(null, unitInput(municipality, "Unidade Demonstração"), "test");
    defender =
        institution.defender(
            new InstitutionService.Defender("defensor", "Defensor Exemplo"), "test");
    other =
        institution.defender(new InstitutionService.Defender("outro", "Outro Defensor"), "test");
    for (var id : List.of(defender, other)) {
      var invite = accounts.invite(id, "test");
      accounts.activate(invite.token(), "test-only-password", "test-only-password");
    }
    affiliation =
        institution.affiliation(
            new InstitutionService.Affiliation(defender, unit, "CIVEL"), "test");
    criminal =
        institution.affiliation(
            new InstitutionService.Affiliation(defender, unit, "CRIMINAL"), "test");
  }

  protected io.github.cgerodrigues.schedula.institution.UnitService.Input unitInput(
      UUID city, String name) {
    return new io.github.cgerodrigues.schedula.institution.UnitService.Input(
        city,
        "CE",
        name,
        Set.of("CIVEL", "CRIMINAL"),
        "(85) 3333-4444",
        "",
        "60175000",
        "Rua Fictícia",
        "100",
        "",
        "Centro",
        true);
  }

  protected UUID publish(
      UUID link, LocalDate day, LocalTime start, LocalTime end, LocalDate repeat) {
    return scheduling.publish(
        defender, new SchedulingService.Publish(link, day, start, end, 30, repeat));
  }

  protected UUID slot(int hour) {
    publish(
        affiliation, LocalDate.of(2030, 1, 8), LocalTime.of(hour, 0), LocalTime.of(hour, 30), null);
    return (UUID) db.one("select id from slot order by starts_at desc limit 1").get("id");
  }

  protected String appointmentStatus(UUID id) {
    return (String) db.one("select status from appointment where id=?", id).get("status");
  }
}
