package io.github.cgerodrigues.schedula;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cgerodrigues.schedula.scheduling.SchedulingService;
import io.github.cgerodrigues.schedula.whatsapp.ConversationService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class PaginationIntegrationTest extends IntegrationBase {
  @Autowired ConversationService conversations;

  private void slots(int count) {
    scheduling.publish(
        defender,
        new SchedulingService.Publish(
            affiliation,
            LocalDate.of(2030, 1, 8),
            LocalTime.of(8, 0),
            LocalTime.of(8, 0).plusMinutes(count * 5),
            5,
            null));
  }

  @ParameterizedTest
  @ValueSource(ints = {5, 10, 25})
  void pagesCoverAllSlotsWithoutDuplication(int count) {
    slots(count);
    Set<Object> ids = new HashSet<>();
    SchedulingService.SlotCursor cursor = null;
    int pages = 0;
    do {
      var page = scheduling.availablePage(unit, "CIVEL", cursor);
      assertTrue(page.items().size() <= 10);
      for (var row : page.items()) assertTrue(ids.add(row.get("id")));
      cursor = page.next();
      pages++;
    } while (cursor != null);
    assertEquals(count, ids.size());
    assertEquals((count + 9) / 10, pages);
  }

  @Test
  void reservedRowsBeforeCursorDoNotSkipTheNextSlot() {
    slots(25);
    var first = scheduling.availablePage(unit, "CIVEL", null);
    appointments.reserve(
        (UUID) first.items().getFirst().get("id"), "550000000001", "Maria", "Silva");
    var second = scheduling.availablePage(unit, "CIVEL", first.next());
    assertEquals(10, second.items().size());
    assertEquals(
        Instant.parse("2030-01-08T11:50:00Z"),
        io.github.cgerodrigues.schedula.appointment.AppointmentService.instant(
            second.items().getFirst(), "starts_at"));
  }

  @Test
  void occupiedAndBlockedSlotsAreExcluded() {
    slots(5);
    var first = scheduling.availablePage(unit, "CIVEL", null);
    appointments.reserve(
        (UUID) first.items().getFirst().get("id"), "550000000001", "Maria", "Silva");
    scheduling.block(
        defender,
        new SchedulingService.Block(
            OffsetDateTime.parse("2030-01-08T08:05:00-03:00"),
            OffsetDateTime.parse("2030-01-08T08:10:00-03:00"),
            "Bloqueio"));
    assertEquals(3, scheduling.availablePage(unit, "CIVEL", null).items().size());
    assertTrue(scheduling.availablePage(unit, "CRIMINAL", null).items().isEmpty());
  }

  private tools.jackson.databind.JsonNode options() {
    return tools.jackson.databind.json.JsonMapper.builder()
        .build()
        .readTree(
            (String)
                db.one("select options from whatsapp_conversation where phone='550000000001'")
                    .get("options"));
  }

  @Test
  void whatsappMoreAndResumePersistCursorAndLastPage() {
    slots(25);
    int id = 0;
    for (String text : List.of("OI", "1", "1", "1"))
      conversations.receive("m" + id++, "550000000001", text);
    assertEquals(10, options().path("ids").size());
    conversations.receive("more1", "550000000001", "MAIS");
    var second = options().path("ids").toString();
    conversations.receive("resume", "550000000001", "RETOMAR");
    assertEquals(second, options().path("ids").toString());
    conversations.receive("more2", "550000000001", "MAIS");
    assertEquals(5, options().path("ids").size());
    var last = options().path("ids").toString();
    conversations.receive("more3", "550000000001", "MAIS");
    assertEquals(last, options().path("ids").toString());
    conversations.receive("choose", "550000000001", "1");
    assertEquals("FIRST_NAME", db.one("select state from whatsapp_conversation").get("state"));
  }

  @Test
  void lastPageDisappearsWithoutError() {
    slots(11);
    var first = scheduling.availablePage(unit, "CIVEL", null);
    var last = scheduling.availablePage(unit, "CIVEL", first.next());
    appointments.reserve(
        (UUID) last.items().getFirst().get("id"), "550000000001", "Maria", "Silva");
    assertTrue(scheduling.availablePage(unit, "CIVEL", first.next()).items().isEmpty());
  }
}
