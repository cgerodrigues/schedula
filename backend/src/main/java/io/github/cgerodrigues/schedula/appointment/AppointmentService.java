package io.github.cgerodrigues.schedula.appointment;

import static io.github.cgerodrigues.schedula.shared.DomainException.require;

import io.github.cgerodrigues.schedula.notification.NotificationService;
import io.github.cgerodrigues.schedula.shared.*;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentService {
  private final Database db;
  private final Clock clock;
  private final NotificationService notifications;

  public AppointmentService(Database db, Clock clock, NotificationService notifications) {
    this.db = db;
    this.clock = clock;
    this.notifications = notifications;
  }

  public static String identity(String first, String last) {
    return Normalizer.normalize((first + " " + last).strip(), Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  public record Booking(UUID id, UUID protocol) {}

  @Transactional(
      noRollbackFor = {
        DomainException.class,
        org.springframework.dao.EmptyResultDataAccessException.class
      })
  public Booking reserve(UUID slot, String phone, String first, String last) {
    require(phone.matches("[0-9]{8,20}"), "Telefone inválido");
    require(
        first != null
            && !first.isBlank()
            && first.length() <= 100
            && last != null
            && !last.isBlank()
            && last.length() <= 150,
        "Informe nome e sobrenome válidos");
    var key = identity(first, last);
    db.lock("citizen:" + phone + ":" + key);
    UUID citizen = UUID.randomUUID();
    db.jdbc.update(
        "insert into citizen(id,phone,first_name,last_name,identity_key) values (?,?,?,?,?) on"
            + " conflict(phone,identity_key) do nothing",
        citizen,
        phone,
        first.strip(),
        last.strip(),
        key);
    citizen =
        (UUID)
            db.one("select id from citizen where phone=? and identity_key=?", phone, key).get("id");
    return book(slot, citizen, null);
  }

  private Map<String, Object> lockSlot(UUID id) {
    var s =
        db.one(
            "select s.*,f.area,f.unit_id from slot s join affiliation f on f.id=s.affiliation_id"
                + " where s.id=?",
            id);
    db.lock("defender:" + s.get("defender_id"));
    db.lock("unit:" + s.get("unit_id"));
    return db.one(
        "select s.*,f.area,u.active,exists(select 1 from unit_area ua where ua.unit_id=u.id and"
            + " ua.area=f.area) offered from slot s join affiliation f on f.id=s.affiliation_id"
            + " join defensoria_unit u on u.id=f.unit_id where s.id=? for update of s",
        id);
  }

  private Booking book(UUID slot, UUID citizen, UUID previous) {
    var s = lockSlot(slot);
    require(
        (boolean) s.get("published")
            && (boolean) s.get("active")
            && (boolean) s.get("offered")
            && instant(s, "starts_at").isAfter(clock.instant()),
        "Vaga indisponível");
    require(
        !db.exists(
            "select exists(select 1 from appointment where slot_id=? and status='CONFIRMADO')",
            slot),
        "Vaga já reservada");
    require(
        !db.exists(
            "select exists(select 1 from appointment where citizen_id=? and area=? and"
                + " status='CONFIRMADO')",
            citizen,
            s.get("area")),
        "Já existe agendamento ativo para esta pessoa nesta área");
    var id = UUID.randomUUID();
    var protocol = UUID.randomUUID();
    db.jdbc.update(
        "insert into appointment(id,protocol,slot_id,citizen_id,area,status,previous_id) values"
            + " (?,?,?,?,?,'CONFIRMADO',?)",
        id,
        protocol,
        slot,
        citizen,
        s.get("area"),
        previous);
    db.audit(
        previous == null ? "whatsapp" : s.get("defender_id").toString(),
        "APPOINTMENT_CONFIRMED",
        id);
    return new Booking(id, protocol);
  }

  private Map<String, Object> appointment(UUID id) {
    return db.one(
        "select a.*,s.defender_id,s.starts_at,c.phone from appointment a join slot s on"
            + " s.id=a.slot_id join citizen c on c.id=a.citizen_id where a.id=? for update of a",
        id);
  }

  private void confirmed(Map<String, Object> a) {
    require(
        a.get("status").equals("CONFIRMADO"),
        "Operação permitida somente em atendimento confirmado");
  }

  private void owner(Map<String, Object> a, UUID owner) {
    require(a.get("defender_id").equals(owner), "Atendimento não pertence ao defensor");
  }

  private void release(Map<String, Object> a) {
    db.jdbc.update(
        "update slot set published=? where id=?",
        !instant(a, "starts_at").isBefore(clock.instant().plus(Duration.ofHours(2))),
        a.get("slot_id"));
  }

  @Transactional
  public void cancel(UUID id, UUID owner) {
    db.lock("defender:" + owner);
    var a = appointment(id);
    owner(a, owner);
    confirmed(a);
    db.jdbc.update("update appointment set status='CANCELADO_DEFENSOR' where id=?", id);
    release(a);
    db.audit(owner.toString(), "APPOINTMENT_CANCELLED", id);
    notifications.enqueue(
        (String) a.get("phone"),
        "Atendimento cancelado pela Defensoria. Protocolo: "
            + a.get("protocol")
            + ". Entre em contato com a unidade para orientação.",
        true);
  }

  @Transactional(
      noRollbackFor = {
        DomainException.class,
        org.springframework.dao.EmptyResultDataAccessException.class
      })
  public void cancelCitizen(UUID protocol, String phone) {
    var found =
        db.one(
            "select a.id,s.defender_id from appointment a join citizen c on c.id=a.citizen_id join"
                + " slot s on s.id=a.slot_id where a.protocol=? and c.phone=?",
            protocol,
            phone);
    db.lock("defender:" + found.get("defender_id"));
    var a = appointment((UUID) found.get("id"));
    confirmed(a);
    require(
        instant(a, "starts_at").atZone(clock.getZone()).toLocalDate().isAfter(LocalDate.now(clock)),
        "Cancelamento pelo cidadão permitido somente até o dia anterior");
    db.jdbc.update("update appointment set status='CANCELADO_CIDADAO' where id=?", a.get("id"));
    release(a);
    db.audit("whatsapp", "CITIZEN_CANCELLED", (UUID) a.get("id"));
  }

  @Transactional
  public Booking reschedule(UUID id, UUID owner, UUID target) {
    db.lock("defender:" + owner);
    var a = appointment(id);
    owner(a, owner);
    confirmed(a);
    require(
        db.exists("select exists(select 1 from slot where id=? and defender_id=?)", target, owner),
        "Nova vaga não pertence ao defensor");
    var s = lockSlot(target);
    require(
        s.get("defender_id").equals(owner) && s.get("area").equals(a.get("area")),
        "Nova vaga deve pertencer ao defensor e à mesma área");
    require(!target.equals(a.get("slot_id")), "Selecione outra vaga");
    db.jdbc.update("update appointment set status='REMARCADO' where id=?", id);
    release(a);
    var next = book(target, (UUID) a.get("citizen_id"), id);
    db.audit(owner.toString(), "APPOINTMENT_RESCHEDULED", id);
    notifications.enqueue(
        (String) a.get("phone"),
        "Atendimento remarcado para "
            + instant(s, "starts_at").atZone(clock.getZone())
            + ". Novo protocolo: "
            + next.protocol(),
        true);
    return next;
  }

  @Transactional
  public void status(UUID id, UUID owner, String status) {
    db.lock("defender:" + owner);
    var a = appointment(id);
    owner(a, owner);
    confirmed(a);
    require(!instant(a, "starts_at").isAfter(clock.instant()), "Aguarde o início do atendimento");
    require(
        Set.of("COMPARECEU", "REALIZADO", "NAO_COMPARECEU").contains(status), "Transição inválida");
    if (status.equals("COMPARECEU"))
      db.jdbc.update(
          "update appointment set attended_at=coalesce(attended_at,?) where id=?",
          OffsetDateTime.now(clock),
          id);
    else {
      require(
          !status.equals("NAO_COMPARECEU") || a.get("attended_at") == null,
          "Comparecimento já registrado");
      db.jdbc.update("update appointment set status=? where id=?", status, id);
      db.jdbc.update("update slot set published=false where id=?", a.get("slot_id"));
    }
    db.audit(owner.toString(), status, id);
  }

  public List<Map<String, Object>> list(UUID owner) {
    return db.list(
        "select a.*,s.starts_at,s.ends_at,c.first_name,c.last_name,c.phone,u.name unit_name from"
            + " appointment a join slot s on s.id=a.slot_id join citizen c on c.id=a.citizen_id"
            + " join affiliation f on f.id=s.affiliation_id join defensoria_unit u on"
            + " u.id=f.unit_id where s.defender_id=? order by s.starts_at desc",
        owner);
  }

  public static Instant instant(Map<String, Object> row, String field) {
    Object value = row.get(field);
    return value instanceof java.sql.Timestamp t
        ? t.toInstant()
        : ((OffsetDateTime) value).toInstant();
  }
}
