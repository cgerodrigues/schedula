package io.github.cgerodrigues.schedula;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cgerodrigues.schedula.institution.InstitutionService;
import io.github.cgerodrigues.schedula.scheduling.SchedulingService;
import io.github.cgerodrigues.schedula.shared.DomainException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BusinessIntegrationTest extends IntegrationBase {
  @Test
  void institutionForeignKeysAndDuplicateLinks() {
    assertEquals("8533334444", institution.units().getFirst().get("phone"));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            institution.affiliation(
                new InstitutionService.Affiliation(defender, unit, "CIVEL"), "test"));
    assertThrows(
        DomainException.class,
        () -> units.save(null, unitInput(UUID.randomUUID(), "Inválida"), "test"));
  }

  @Test
  void weeklyPublicationAndCrossAreaConflict() {
    publish(
        affiliation,
        LocalDate.of(2030, 1, 8),
        LocalTime.of(9, 0),
        LocalTime.of(10, 0),
        LocalDate.of(2030, 1, 22));
    assertEquals(6, scheduling.available(unit, "CIVEL").size());
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            publish(
                criminal,
                LocalDate.of(2030, 1, 8),
                LocalTime.of(9, 30),
                LocalTime.of(10, 0),
                null));
    assertEquals(
        1, db.jdbc.queryForObject("select count(*) from availability_series", Integer.class));
  }

  @Test
  void ownershipAndPastPublication() {
    assertThrows(
        DomainException.class,
        () ->
            scheduling.publish(
                other,
                new SchedulingService.Publish(
                    affiliation,
                    LocalDate.of(2030, 1, 8),
                    LocalTime.of(9, 0),
                    LocalTime.of(10, 0),
                    30,
                    null)));
    assertThrows(
        DomainException.class,
        () ->
            publish(
                affiliation,
                LocalDate.of(2030, 1, 6),
                LocalTime.of(9, 0),
                LocalTime.of(10, 0),
                null));
  }

  @Test
  void blockingPreservesReservations() {
    var s = slot(9);
    var a = appointments.reserve(s, "550000000001", "Maria", "Exemplo");
    var block =
        new SchedulingService.Block(
            OffsetDateTime.parse("2030-01-08T08:00:00-03:00"),
            OffsetDateTime.parse("2030-01-08T10:00:00-03:00"),
            "Ausência");
    assertThrows(DomainException.class, () -> scheduling.block(defender, block));
    assertEquals("CONFIRMADO", appointmentStatus(a.id()));
    appointments.cancel(a.id(), defender);
    scheduling.block(defender, block);
    assertTrue(scheduling.available(unit, "CIVEL").isEmpty());
    assertThrows(
        DomainException.class,
        () ->
            publish(
                affiliation,
                LocalDate.of(2030, 1, 8),
                LocalTime.of(8, 0),
                LocalTime.of(8, 30),
                null));
  }

  @Test
  void citizenAreaLimitAndSharedPhone() {
    var a = slot(9);
    var b = slot(10);
    var c = slot(11);
    appointments.reserve(a, "550000000001", "José", "Silva");
    assertThrows(
        DomainException.class, () -> appointments.reserve(b, "550000000001", "  JOSE ", "silva"));
    appointments.reserve(b, "550000000001", "Ana", "Silva");
    publish(criminal, LocalDate.of(2030, 1, 8), LocalTime.of(12, 0), LocalTime.of(12, 30), null);
    appointments.reserve(
        (UUID) scheduling.available(unit, "CRIMINAL").getFirst().get("id"),
        "550000000001",
        "José",
        "Silva");
    assertEquals(3, db.jdbc.queryForObject("select count(*) from appointment", Integer.class));
    assertNotNull(c);
  }

  @Test
  void concurrentCitizensCannotReserveSameSlot() throws Exception {
    UUID s = slot(9);
    var gate = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 8; i++) {
        int n = i;
        futures.add(
            executor.submit(
                () -> {
                  gate.await();
                  try {
                    appointments.reserve(s, "55000000000" + n, "Pessoa", "Exemplo" + n);
                    return true;
                  } catch (DomainException e) {
                    return false;
                  }
                }));
      }
      gate.countDown();
      int count = 0;
      for (var f : futures) if (f.get(20, TimeUnit.SECONDS)) count++;
      assertEquals(1, count);
    }
    assertEquals(1, db.jdbc.queryForObject("select count(*) from appointment", Integer.class));
  }

  @Test
  void concurrentSameCitizenCannotReserveTwoSlots() throws Exception {
    UUID a = slot(9), b = slot(10);
    var gate = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results = new ArrayList<Future<Boolean>>();
      for (UUID s : List.of(a, b))
        results.add(
            executor.submit(
                () -> {
                  gate.await();
                  try {
                    appointments.reserve(s, "550000000001", "Maria", "Silva");
                    return true;
                  } catch (DomainException e) {
                    return false;
                  }
                }));
      gate.countDown();
      int count = 0;
      for (var f : results) if (f.get(20, TimeUnit.SECONDS)) count++;
      assertEquals(1, count);
    }
  }

  @Test
  void cancellationDeadlineAndTwoHourRelease() {
    var s = slot(9);
    var a = appointments.reserve(s, "550000000001", "Maria", "Silva");
    appointments.cancelCitizen(a.protocol(), "550000000001");
    assertEquals("CANCELADO_CIDADAO", appointmentStatus(a.id()));
    assertEquals(1, scheduling.available(unit, "CIVEL").size());
    publish(affiliation, LocalDate.of(2030, 1, 7), LocalTime.of(13, 0), LocalTime.of(13, 30), null);
    UUID close =
        (UUID) db.one("select id from slot where starts_at='2030-01-07T13:00:00-03:00'").get("id");
    var today = appointments.reserve(close, "550000000001", "Maria", "Silva");
    assertThrows(
        DomainException.class, () -> appointments.cancelCitizen(today.protocol(), "550000000001"));
    appointments.cancel(today.id(), defender);
    assertFalse((boolean) db.one("select published from slot where id=?", close).get("published"));
    assertEquals(
        1,
        db.jdbc.queryForObject(
            "select count(*) from notification_outbox where template", Integer.class));
  }

  @Test
  void exactTwoHoursReopensAndCitizenPhoneIsChecked() {
    publish(affiliation, LocalDate.of(2030, 1, 7), LocalTime.of(14, 0), LocalTime.of(14, 30), null);
    UUID s = (UUID) db.one("select id from slot").get("id");
    var a = appointments.reserve(s, "550000000001", "Maria", "Silva");
    assertThrows(
        org.springframework.dao.EmptyResultDataAccessException.class,
        () -> appointments.cancelCitizen(a.protocol(), "550000000002"));
    appointments.cancel(a.id(), defender);
    assertTrue((boolean) db.one("select published from slot where id=?", s).get("published"));
  }

  @Test
  void reschedulePreservesHistoryAndRollsBackUnavailableTarget() {
    var a = appointments.reserve(slot(9), "550000000001", "Maria", "Silva");
    UUID target = slot(10);
    assertThrows(DomainException.class, () -> appointments.cancel(a.id(), other));
    var next = appointments.reschedule(a.id(), defender, target);
    assertEquals("REMARCADO", appointmentStatus(a.id()));
    assertEquals("CONFIRMADO", appointmentStatus(next.id()));
    assertEquals(
        a.id(),
        db.one("select previous_id from appointment where id=?", next.id()).get("previous_id"));
    var taken = appointments.reserve(slot(11), "550000000002", "Ana", "Silva");
    var takenSlot =
        (UUID) db.one("select slot_id from appointment where id=?", taken.id()).get("slot_id");
    assertThrows(
        DomainException.class, () -> appointments.reschedule(next.id(), defender, takenSlot));
    assertEquals("CONFIRMADO", appointmentStatus(next.id()));
  }

  @Test
  void attendanceAndTerminalTransitions() {
    var a = appointments.reserve(slot(9), "550000000001", "Maria", "Silva");
    assertThrows(DomainException.class, () -> appointments.status(a.id(), defender, "REALIZADO"));
    db.jdbc.update(
        "update slot set starts_at='2030-01-07T09:00:00-03:00',ends_at='2030-01-07T09:30:00-03:00'"
            + " where id=(select slot_id from appointment where id=?)",
        a.id());
    appointments.status(a.id(), defender, "COMPARECEU");
    assertThrows(
        DomainException.class, () -> appointments.status(a.id(), defender, "NAO_COMPARECEU"));
    appointments.status(a.id(), defender, "REALIZADO");
    assertThrows(DomainException.class, () -> appointments.cancel(a.id(), defender));
    var b = appointments.reserve(slot(10), "550000000001", "Maria", "Silva");
    db.jdbc.update(
        "update slot set starts_at='2030-01-07T10:00:00-03:00',ends_at='2030-01-07T10:30:00-03:00'"
            + " where id=(select slot_id from appointment where id=?)",
        b.id());
    appointments.status(b.id(), defender, "NAO_COMPARECEU");
    appointments.reserve(slot(11), "550000000001", "Maria", "Silva");
    assertEquals("NAO_COMPARECEU", appointmentStatus(b.id()));
  }

  @Test
  void withdrawingSeriesCannotSilentlyCancel() {
    UUID s = slot(9);
    UUID series = (UUID) db.one("select series_id from slot where id=?", s).get("series_id");
    var a = appointments.reserve(s, "550000000001", "Maria", "Silva");
    assertThrows(DomainException.class, () -> scheduling.withdraw(defender, series));
    appointments.cancel(a.id(), defender);
    scheduling.withdraw(defender, series);
    publish(affiliation, LocalDate.of(2030, 1, 8), LocalTime.of(9, 0), LocalTime.of(9, 30), null);
  }
}
