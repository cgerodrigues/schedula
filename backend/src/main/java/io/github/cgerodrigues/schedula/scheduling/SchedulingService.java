package io.github.cgerodrigues.schedula.scheduling;

import static io.github.cgerodrigues.schedula.shared.DomainException.require;

import io.github.cgerodrigues.schedula.shared.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchedulingService {
  private final Database db;
  private final Clock clock;

  public SchedulingService(Database db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public record Publish(
      @NotNull UUID affiliationId,
      @NotNull LocalDate date,
      @NotNull LocalTime start,
      @NotNull LocalTime end,
      @Min(5) @Max(240) int minutes,
      LocalDate repeatUntil) {}

  public record Block(
      @NotNull OffsetDateTime start,
      @NotNull OffsetDateTime end,
      @NotBlank @Size(max = 250) String reason) {}

  @Transactional
  public UUID publish(UUID owner, Publish r) {
    db.lock("defender:" + owner);
    var link = db.one("select unit_id from affiliation where id=?", r.affiliationId());
    db.lock("unit:" + link.get("unit_id"));
    require(
        db.exists(
            "select exists(select 1 from affiliation a join defensoria_unit u on u.id=a.unit_id"
                + " where a.id=? and a.defender_id=? and u.active and exists(select 1 from"
                + " unit_area ua where ua.unit_id=u.id and ua.area=a.area))",
            r.affiliationId(),
            owner),
        "Vínculo não autorizado");
    require(r.end().isAfter(r.start()), "Fim deve ser posterior ao início");
    LocalDate until = r.repeatUntil() == null ? r.date() : r.repeatUntil();
    require(!until.isBefore(r.date()), "Fim da recorrência inválido");
    require(
        java.time.temporal.ChronoUnit.WEEKS.between(r.date(), until) <= 520,
        "Publique no máximo 520 semanas por operação; novas publicações podem estender a agenda");
    require(
        Duration.between(r.start(), r.end()).toMinutes() % r.minutes() == 0,
        "Período deve ser divisível pela duração");
    UUID series = UUID.randomUUID();
    db.jdbc.update("insert into availability_series(id,defender_id) values (?,?)", series, owner);
    for (LocalDate date = r.date(); !date.isAfter(until); date = date.plusWeeks(1)) {
      var end = date.atTime(r.end()).atZone(clock.getZone()).toOffsetDateTime();
      for (var start = date.atTime(r.start()).atZone(clock.getZone()).toOffsetDateTime();
          start.isBefore(end);
          start = start.plusMinutes(r.minutes())) {
        var finish = start.plusMinutes(r.minutes());
        require(start.toInstant().isAfter(clock.instant()), "Publique somente horários futuros");
        require(
            !db.exists(
                "select exists(select 1 from schedule_block where defender_id=? and starts_at<? and"
                    + " ends_at>?)",
                owner,
                finish,
                start),
            "Período bloqueado");
        require(
            !db.exists(
                "select exists(select 1 from appointment a join slot s on s.id=a.slot_id where"
                    + " s.defender_id=? and a.status in"
                    + " ('CANCELADO_CIDADAO','CANCELADO_DEFENSOR','REMARCADO') and s.starts_at<?"
                    + " and s.ends_at>? and s.starts_at<?)",
                owner,
                finish,
                start,
                OffsetDateTime.now(clock).plusHours(2)),
            "Vaga cancelada a menos de duas horas não pode ser republicada");
        db.jdbc.update(
            "insert into slot(id,affiliation_id,defender_id,series_id,starts_at,ends_at) values"
                + " (?,?,?,?,?,?)",
            UUID.randomUUID(),
            r.affiliationId(),
            owner,
            series,
            start,
            finish);
      }
    }
    db.audit(owner.toString(), "AVAILABILITY_PUBLISHED", series);
    return series;
  }

  @Transactional
  public UUID block(UUID owner, Block r) {
    require(r.end().isAfter(r.start()), "Período inválido");
    db.lock("defender:" + owner);
    require(
        !db.exists(
            "select exists(select 1 from appointment a join slot s on s.id=a.slot_id where"
                + " s.defender_id=? and a.status='CONFIRMADO' and s.starts_at<? and s.ends_at>?)",
            owner,
            r.end(),
            r.start()),
        "Há reservas confirmadas. Cancele ou remarque explicitamente antes de bloquear.");
    UUID id = UUID.randomUUID();
    db.jdbc.update(
        "insert into schedule_block values (?,?,?,?,?)", id, owner, r.start(), r.end(), r.reason());
    db.jdbc.update(
        "update slot set published=false where defender_id=? and starts_at<? and ends_at>?",
        owner,
        r.end(),
        r.start());
    db.audit(owner.toString(), "PERIOD_BLOCKED", id);
    return id;
  }

  @Transactional
  public void withdraw(UUID owner, UUID series) {
    db.lock("defender:" + owner);
    require(
        db.exists(
            "select exists(select 1 from availability_series where id=? and defender_id=?)",
            series,
            owner),
        "Recorrência não pertence ao defensor");
    require(
        !db.exists(
            "select exists(select 1 from appointment a join slot s on s.id=a.slot_id where"
                + " s.series_id=? and a.status='CONFIRMADO')",
            series),
        "Recorrência possui reservas confirmadas; trate-as explicitamente antes de substituir.");
    db.jdbc.update("update slot set published=false where series_id=?", series);
    db.audit(owner.toString(), "SERIES_WITHDRAWN", series);
  }

  public List<Map<String, Object>> slots(UUID owner) {
    return db.list(
        "select s.*,a.area,u.name unit_name from slot s join affiliation a on a.id=s.affiliation_id"
            + " join defensoria_unit u on u.id=a.unit_id where s.defender_id=? order by starts_at",
        owner);
  }

  public List<Map<String, Object>> blocks(UUID owner) {
    return db.list("select * from schedule_block where defender_id=? order by starts_at", owner);
  }

  public record SlotCursor(OffsetDateTime startsAt, UUID id) {}

  public record SlotPage(List<Map<String, Object>> items, SlotCursor next) {}

  public List<Map<String, Object>> available(UUID unit, String area) {
    return availablePage(unit, area, null).items();
  }

  public SlotPage availablePage(UUID unit, String area, SlotCursor after) {
    // One extra row detects the next page; the database never returns the full calendar.
    var rows =
        db.list(
"""
select s.id,s.starts_at,s.ends_at,u.name unit_name
from slot s join affiliation f on f.id=s.affiliation_id
join defensoria_unit u on u.id=f.unit_id
where f.unit_id=? and f.area=? and u.active and s.published and s.starts_at>?
  and exists(select 1 from unit_area ua where ua.unit_id=u.id and ua.area=f.area)
  and not exists(select 1 from appointment a where a.slot_id=s.id and a.status='CONFIRMADO')
  and (?::timestamptz is null or (s.starts_at,s.id) > (?::timestamptz,?::uuid))
order by s.starts_at,s.id limit 11
""",
            unit,
            area,
            OffsetDateTime.now(clock),
            after == null ? null : after.startsAt(),
            after == null ? null : after.startsAt(),
            after == null ? null : after.id());
    var items = List.copyOf(rows.subList(0, Math.min(10, rows.size())));
    SlotCursor next = null;
    if (rows.size() > 10) {
      var last = items.getLast();
      next =
          new SlotCursor(
              io.github.cgerodrigues.schedula.appointment.AppointmentService.instant(
                      last, "starts_at")
                  .atOffset(ZoneOffset.UTC),
              (UUID) last.get("id"));
    }
    return new SlotPage(items, next);
  }
}
