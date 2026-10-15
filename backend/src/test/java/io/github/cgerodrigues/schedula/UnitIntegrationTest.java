package io.github.cgerodrigues.schedula;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cgerodrigues.schedula.institution.*;
import io.github.cgerodrigues.schedula.shared.DomainException;
import io.github.cgerodrigues.schedula.whatsapp.ConversationService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

class UnitIntegrationTest extends IntegrationBase {
  @Autowired MockMvc mvc;
  @Autowired ConversationService conversations;
  final JsonMapper json = JsonMapper.builder().build();

  UnitService.Input input(String name, Set<String> areas, boolean active) {
    return new UnitService.Input(
        municipality,
        "CE",
        name,
        areas,
        "(85) 99999-9999",
        "unidade@example.org",
        "60175-000",
        "Rua Nova",
        "S/N",
        "Sala 2",
        "Bairro Novo",
        active);
  }

  @Test
  void createsEachAreaCombinationWithoutDefenders() {
    for (var areas : List.of(Set.of("CIVEL"), Set.of("CRIMINAL"), Set.of("CIVEL", "CRIMINAL"))) {
      var id = units.save(null, input(" Unidade   " + areas, areas, true), "rh");
      assertEquals(
          areas.size(),
          db.jdbc.queryForObject(
              "select count(*) from unit_area where unit_id=?", Integer.class, id));
      assertEquals(
          0,
          db.jdbc.queryForObject(
              "select count(*) from affiliation where unit_id=?", Integer.class, id));
      assertEquals(
          "85999999999", db.one("select phone from defensoria_unit where id=?", id).get("phone"));
    }
  }

  @Test
  void fullEditPreservesIdentityOriginalAddressAndHistory() {
    var slot = slot(10);
    var booking = appointments.reserve(slot, "5585999999999", "Pessoa", "Exemplo");
    var before = db.one("select * from defensoria_unit where id=?", unit);
    assertEquals(
        unit, units.save(unit, input("Novo nome", Set.of("CIVEL", "CRIMINAL"), true), "rh"));
    var after = db.one("select * from defensoria_unit where id=?", unit);
    assertEquals(before.get("address"), after.get("address"));
    assertEquals("Novo nome", after.get("name"));
    assertEquals("Rua Nova", after.get("street"));
    assertEquals("S/N", after.get("street_number"));
    assertEquals("Bairro Novo", after.get("neighborhood"));
    assertEquals("Sala 2", after.get("complement"));
    assertEquals("60175000", after.get("postal_code"));
    assertEquals("unidade@example.org", after.get("email"));
    assertEquals("85999999999", after.get("phone"));
    assertEquals(
        affiliation,
        db.one("select affiliation_id from slot where id=?", slot).get("affiliation_id"));
    assertEquals("CONFIRMADO", appointmentStatus(booking.id()));
    assertTrue(
        db.exists(
            "select exists(select 1 from audit_event where resource_id=? and"
                + " action='UNIT_UPDATED')",
            unit));
  }

  @Test
  void preservesUntouchedLegacyPhoneButValidatesReplacement() {
    db.jdbc.update("update defensoria_unit set phone='telefone antigo' where id=?", unit);
    var data = json.convertValue(input("Nome", Set.of("CIVEL", "CRIMINAL"), true), Map.class);
    var mutable = new HashMap<String, Object>(data);
    mutable.put("phone", "telefone antigo");
    units.save(unit, json.convertValue(mutable, UnitService.Input.class), "rh");
    assertEquals(
        "telefone antigo",
        db.one("select phone from defensoria_unit where id=?", unit).get("phone"));
    mutable.put("phone", "(20) 3333-4444");
    assertThrows(
        DomainException.class,
        () -> units.save(unit, json.convertValue(mutable, UnitService.Input.class), "rh"));
  }

  @Test
  void validatesRequiredFieldsOfficialCityStateAndEmailThroughApi() throws Exception {
    var original = json.convertValue(input("Nova", Set.of("CIVEL"), true), Map.class);
    for (var invalid :
        List.of(
            Map.entry("areas", List.of()),
            Map.entry("municipalityId", ""),
            Map.entry("stateCode", "PE"),
            Map.entry("postalCode", "123"),
            Map.entry("email", "invalido"),
            Map.entry("phone", "(85) 3333-4444x"))) {
      var data = new HashMap<String, Object>(original);
      data.put(invalid.getKey(), invalid.getValue());
      var result =
          mvc.perform(
                  post("/api/rh/units")
                      .with(user("rh").roles("RH"))
                      .with(csrf())
                      .contentType("application/json")
                      .content(json.writeValueAsString(data)))
              .andReturn();
      assertTrue(Set.of(400, 409).contains(result.getResponse().getStatus()), invalid.getKey());
    }
    db.jdbc.update("update municipality set ibge_code=null where id=?", municipality);
    assertThrows(
        DomainException.class, () -> units.save(null, input("Nova", Set.of("CIVEL"), true), "rh"));
  }

  @Test
  void editIsRestrictedToRhAndRequiresCsrf() throws Exception {
    var body = json.writeValueAsString(input("Novo", Set.of("CIVEL", "CRIMINAL"), true));
    mvc.perform(
            put("/api/rh/units/" + unit)
                .with(user("defensor").roles("DEFENSOR"))
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/api/rh/units/" + unit)
                .with(user("rh").roles("RH"))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/api/rh/units/" + unit)
                .with(user("rh").roles("RH"))
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(unit.toString()));
  }

  @Test
  void changingCityAllowedWithoutDependenciesAndRejectedWithHistory() {
    UUID city = UUID.randomUUID();
    db.jdbc.update(
        "insert into municipality(id,name,state_code,ibge_code) values"
            + " (?,'Caucaia','CE','2303709')",
        city);
    var id = units.save(null, input("Sem vínculos", Set.of("CIVEL"), true), "rh");
    units.save(id, unitInput(city, "Transferida"), "rh");
    assertEquals(
        city,
        db.one("select municipality_id from defensoria_unit where id=?", id)
            .get("municipality_id"));
    assertThrows(
        DomainException.class, () -> units.save(unit, unitInput(city, "Transferida"), "rh"));
  }

  @Test
  void addingAndRemovingUnlinkedAreaDoesNotCreateSchedulesOrLinks() {
    var id = units.save(null, input("Sem vínculos", Set.of("CIVEL"), true), "rh");
    units.save(id, input("Sem vínculos", Set.of("CIVEL", "CRIMINAL"), true), "rh");
    units.save(id, input("Sem vínculos", Set.of("CRIMINAL"), true), "rh");
    assertEquals("CRIMINAL", db.one("select area from unit_area where unit_id=?", id).get("area"));
    assertFalse(db.exists("select exists(select 1 from affiliation where unit_id=?)", id));
    assertEquals(0, db.jdbc.queryForObject("select count(*) from slot", Integer.class));
  }

  @Test
  void removingAreaWithActiveLinkIsRejected() {
    var e =
        assertThrows(
            DomainException.class,
            () -> units.save(unit, input("Nome", Set.of("CIVEL"), true), "rh"));
    assertTrue(e.getMessage().contains("vínculos"));
  }

  @Test
  void removingAreaWithPublishedAvailabilityIsRejected() {
    slot(10);
    var e =
        assertThrows(
            DomainException.class,
            () -> units.save(unit, input("Nome", Set.of("CRIMINAL"), true), "rh"));
    assertTrue(e.getMessage().contains("disponibilidades"));
  }

  @Test
  void removingAreaOrDeactivatingWithReservationPreservesIt() {
    var id = slot(10);
    var booking = appointments.reserve(id, "5585999999999", "Pessoa", "Exemplo");
    db.jdbc.update("update slot set published=false where id=?", id);
    assertThrows(
        DomainException.class,
        () -> units.save(unit, input("Nome", Set.of("CRIMINAL"), true), "rh"));
    assertThrows(
        DomainException.class,
        () -> units.save(unit, input("Nome", Set.of("CIVEL", "CRIMINAL"), false), "rh"));
    assertEquals("CONFIRMADO", appointmentStatus(booking.id()));
  }

  @Test
  void inactiveUnitsRejectPublicationAndBooking() {
    units.save(unit, input("Nome", Set.of("CIVEL", "CRIMINAL"), false), "rh");
    assertThrows(DomainException.class, () -> slot(10));
    db.jdbc.update("update defensoria_unit set active=true where id=?", unit);
    var id = slot(10);
    db.jdbc.update("update slot set published=false where id=?", id);
    units.save(unit, input("Nome", Set.of("CIVEL", "CRIMINAL"), false), "rh");
    assertThrows(
        DomainException.class,
        () -> appointments.reserve(id, "5585999999999", "Pessoa", "Exemplo"));
  }

  @Test
  void unavailableAreaRejectsLinksAndPublicationEvenWithLegacyLink() {
    db.jdbc.update("delete from unit_area where unit_id=? and area='CRIMINAL'", unit);
    assertThrows(
        DomainException.class,
        () ->
            institution.affiliation(
                new InstitutionService.Affiliation(other, unit, "CRIMINAL"), "rh"));
    assertThrows(
        DomainException.class,
        () ->
            publish(
                criminal,
                LocalDate.of(2030, 1, 8),
                LocalTime.of(10, 0),
                LocalTime.of(11, 0),
                null));
  }

  @Test
  void whatsappOffersUnstaffedAreaAndContactInsteadOfInventingAreas() {
    db.jdbc.update("delete from affiliation where unit_id=?", unit);
    units.save(unit, input("Única unidade", Set.of("CRIMINAL"), true), "rh");
    String phone = "5585988888888";
    for (String text : List.of("MENU", "1", "1"))
      conversations.receive(UUID.randomUUID().toString(), phone, text);
    String area =
        (String)
            db.one("select body from notification_outbox order by created_at desc limit 1")
                .get("body");
    assertTrue(area.contains("1 — Criminal"));
    assertFalse(area.contains("Cível"));
    conversations.receive(UUID.randomUUID().toString(), phone, "1");
    String message =
        (String)
            db.one("select body from notification_outbox order by created_at desc limit 1")
                .get("body");
    assertTrue(message.contains("Não há vagas"));
    assertTrue(message.contains("85999999999"));
  }

  @Test
  void simultaneousNormalizedDuplicateCreationHasOneWinner() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var calls =
          List.of("  Unidade   Nova ", "unidade nova").stream()
              .map(
                  name ->
                      pool.submit(
                          () -> {
                            start.await();
                            try {
                              units.save(null, input(name, Set.of("CIVEL"), true), "rh");
                              return true;
                            } catch (DomainException
                                | org.springframework.dao.DataIntegrityViolationException e) {
                              return false;
                            }
                          }))
              .toList();
      start.countDown();
      int winners = 0;
      for (var call : calls) if (call.get(10, TimeUnit.SECONDS)) winners++;
      assertEquals(1, winners);
    }
  }

  @Test
  void deactivationRacingPublicationNeverLeavesInactivePublishedAgenda() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var publishing =
          pool.submit(
              () -> {
                start.await();
                try {
                  slot(10);
                  return true;
                } catch (DomainException e) {
                  return false;
                }
              });
      var editing =
          pool.submit(
              () -> {
                start.await();
                try {
                  units.save(unit, input("Nome", Set.of("CIVEL", "CRIMINAL"), false), "rh");
                  return true;
                } catch (DomainException e) {
                  return false;
                }
              });
      start.countDown();
      assertNotEquals(publishing.get(10, TimeUnit.SECONDS), editing.get(10, TimeUnit.SECONDS));
      assertFalse(
          db.exists(
              "select exists(select 1 from slot s join affiliation a on a.id=s.affiliation_id join"
                  + " defensoria_unit u on u.id=a.unit_id where not u.active and s.published)"));
    }
  }

  @Test
  void areaRemovalRacingAffiliationCannotLeaveIncompatibleLink() throws Exception {
    var id = units.save(null, input("Concorrente", Set.of("CIVEL", "CRIMINAL"), true), "rh");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var linking =
          pool.submit(
              () -> {
                start.await();
                try {
                  institution.affiliation(
                      new InstitutionService.Affiliation(other, id, "CRIMINAL"), "rh");
                  return true;
                } catch (DomainException e) {
                  return false;
                }
              });
      var editing =
          pool.submit(
              () -> {
                start.await();
                try {
                  units.save(id, input("Concorrente", Set.of("CIVEL"), true), "rh");
                  return true;
                } catch (DomainException e) {
                  return false;
                }
              });
      start.countDown();
      assertNotEquals(linking.get(10, TimeUnit.SECONDS), editing.get(10, TimeUnit.SECONDS));
      assertFalse(
          db.exists(
              "select exists(select 1 from affiliation a where a.unit_id=? and not exists(select 1"
                  + " from unit_area ua where ua.unit_id=a.unit_id and ua.area=a.area))",
              id));
    }
  }
}
