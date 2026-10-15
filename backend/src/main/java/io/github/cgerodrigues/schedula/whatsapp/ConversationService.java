package io.github.cgerodrigues.schedula.whatsapp;

import static io.github.cgerodrigues.schedula.shared.DomainException.require;

import io.github.cgerodrigues.schedula.appointment.AppointmentService;
import io.github.cgerodrigues.schedula.notification.NotificationService;
import io.github.cgerodrigues.schedula.scheduling.SchedulingService;
import io.github.cgerodrigues.schedula.shared.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ConversationService {
  private final Database db;
  private final SchedulingService scheduling;
  private final AppointmentService appointments;
  private final NotificationService notifications;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public ConversationService(
      Database d, SchedulingService s, AppointmentService a, NotificationService n, Clock c) {
    db = d;
    scheduling = s;
    appointments = a;
    notifications = n;
    clock = c;
  }

  @Transactional
  public void receive(String messageId, String phone, String text) {
    require(
        !messageId.isBlank()
            && messageId.length() <= 250
            && phone.matches("[0-9]{8,20}")
            && text.length() <= 4096,
        "Mensagem inválida");
    db.lock("conversation:" + phone);
    if (db.jdbc.update(
            "insert into whatsapp_inbox(message_id) values (?) on conflict do nothing", messageId)
        == 0) return;
    db.jdbc.update(
        "insert into whatsapp_conversation(phone) values (?) on conflict do nothing", phone);
    var c = db.one("select * from whatsapp_conversation where phone=?", phone);
    String answer;
    try {
      answer = respond(phone, text.strip(), c);
    } catch (DomainException
        | IllegalArgumentException
        | org.springframework.dao.EmptyResultDataAccessException e) {
      answer =
          e instanceof DomainException
              ? e.getMessage() + ". Envie MENU para reiniciar ou RETOMAR para rever as opções."
              : "Opção inválida. Envie MENU para reiniciar ou RETOMAR para rever as opções.";
    }
    db.jdbc.update("update whatsapp_conversation set updated_at=now() where phone=?", phone);
    notifications.enqueue(phone, answer, false);
  }

  private String respond(String phone, String text, Map<String, Object> c) {
    if (text.equalsIgnoreCase("URGENTE"))
      return "Para atendimento urgente, procure a Defensoria da sua região. Urgências não são"
          + " encaixadas automaticamente. "
          + contact(c);
    if (text.toUpperCase(Locale.ROOT).startsWith("CANCELAR ")) {
      appointments.cancelCitizen(UUID.fromString(text.substring(9).strip()), phone);
      return "Agendamento cancelado. Envie MENU para iniciar outro atendimento.";
    }
    if (text.equalsIgnoreCase("MENU")
        || text.equalsIgnoreCase("OI")
        || text.equalsIgnoreCase("OLÁ")) {
      db.jdbc.update(
          "update whatsapp_conversation set"
              + " state='MUNICIPALITY',municipality_id=null,unit_id=null,area=null,slot_id=null,first_name=null,last_name=null"
              + " where phone=?",
          phone);
      return municipalities(phone);
    }
    if (text.equalsIgnoreCase("RETOMAR")) return prompt(phone, c);
    String state = (String) c.get("state");
    switch (state) {
      case "MUNICIPALITY":
        {
          if (c.get("options").equals("[]")) return municipalities(phone);
          UUID selected = choice(c, text);
          db.jdbc.update(
              "update whatsapp_conversation set municipality_id=?,state='UNIT' where phone=?",
              selected,
              phone);
          return units(phone, selected);
        }
      case "UNIT":
        {
          UUID selected = choice(c, text);
          db.jdbc.update(
              "update whatsapp_conversation set unit_id=?,state='AREA' where phone=?",
              selected,
              phone);
          return areas(phone, selected);
        }
      case "AREA":
        {
          var ids = json.readTree((String) c.get("options")).path("ids");
          int n = Integer.parseInt(text);
          require(n > 0 && n <= ids.size(), "Selecione uma área exibida ou envie RETOMAR");
          String area = ids.get(n - 1).asText();
          require(
              db.exists(
                  "select exists(select 1 from unit_area where unit_id=? and area=?)",
                  c.get("unit_id"),
                  area),
              "Área não mais oferecida; envie MENU");
          db.jdbc.update(
              "update whatsapp_conversation set area=?,state='SLOT' where phone=?", area, phone);
          return slots(phone, (UUID) c.get("unit_id"), area, null);
        }
      case "SLOT":
        {
          if (text.equalsIgnoreCase("MAIS")) {
            var options = json.readTree((String) c.get("options"));
            var next = cursor(options.path("next"));
            if (next == null)
              return "Você está na última página. Escolha uma opção exibida ou envie MENU para"
                  + " iniciar nova consulta.";
            return slots(phone, (UUID) c.get("unit_id"), (String) c.get("area"), next);
          }
          UUID selected = choice(c, text);
          db.jdbc.update(
              "update whatsapp_conversation set slot_id=?,state='FIRST_NAME' where phone=?",
              selected,
              phone);
          return "Qual é o seu nome? Não envie CPF ou documentos.";
        }
      case "FIRST_NAME":
        {
          require(
              !text.isBlank() && text.length() <= 100, "Informe um nome de até 100 caracteres.");
          db.jdbc.update(
              "update whatsapp_conversation set first_name=?,state='LAST_NAME' where phone=?",
              text,
              phone);
          return "Qual é o seu sobrenome?";
        }
      case "LAST_NAME":
        {
          require(
              !text.isBlank() && text.length() <= 150,
              "Informe um sobrenome de até 150 caracteres.");
          db.jdbc.update(
              "update whatsapp_conversation set last_name=?,state='CONFIRM' where phone=?",
              text,
              phone);
          return confirmation(c, text);
        }
      case "CONFIRM":
        {
          require(text.equalsIgnoreCase("SIM"), "Envie SIM para confirmar ou MENU para recomeçar.");
          var booking =
              appointments.reserve(
                  (UUID) c.get("slot_id"),
                  phone,
                  (String) c.get("first_name"),
                  (String) c.get("last_name"));
          db.jdbc.update("update whatsapp_conversation set state='DONE' where phone=?", phone);
          return "Agendamento confirmado! Protocolo: "
              + booking.protocol()
              + ". "
              + details((UUID) c.get("slot_id"))
              + " Para cancelar até o dia anterior, envie CANCELAR seguido do protocolo. Envie MENU"
              + " para outra pessoa ou área.";
        }
      default:
        return "Seu atendimento já foi registrado. Envie MENU para iniciar outro, CANCELAR seguido"
            + " do protocolo para cancelar, ou URGENTE para orientação.";
    }
  }

  private String confirmation(Map<String, Object> c, String last) {
    return "Confirme: "
        + c.get("first_name")
        + " "
        + last
        + ". "
        + details((UUID) c.get("slot_id"))
        + " Área: "
        + c.get("area")
        + ". Envie SIM para reservar ou MENU para recomeçar. Usamos nome e telefone para o"
        + " agendamento, sem validação de identidade civil.";
  }

  private String details(UUID slot) {
    var s =
        db.one(
            "select s.starts_at,u.name,case when u.street is null then u.address else concat_ws(',"
                + " ',u.street,u.street_number,nullif(u.complement,''),u.neighborhood) end"
                + " address,u.phone from slot s join affiliation a on a.id=s.affiliation_id join"
                + " defensoria_unit u on u.id=a.unit_id where s.id=?",
            slot);
    return date(s)
        + " — "
        + s.get("name")
        + ", "
        + s.get("address")
        + ". Telefone: "
        + s.get("phone")
        + ".";
  }

  private String date(Map<String, Object> row) {
    return AppointmentService.instant(row, "starts_at")
        .atZone(clock.getZone())
        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
  }

  private String municipalities(String phone) {
    return menu(
        phone,
        "Bem-vindo ao Schedula. Escolha o município (responda com o número). MENU reinicia; RETOMAR"
            + " repete; URGENTE orienta.",
        db.list(
            "select m.id,m.name from municipality m where exists(select 1 from defensoria_unit u"
                + " where u.municipality_id=m.id and u.active) order by m.name"),
        0);
  }

  private String areas(String phone, UUID unit) {
    return menu(
        phone,
        "Escolha a área:",
        db.list(
            "select area id,case area when 'CIVEL' then 'Cível' else 'Criminal' end name from"
                + " unit_area where unit_id=? order by area",
            unit),
        0);
  }

  private String units(String phone, UUID municipality) {
    return menu(
        phone,
        "Escolha a unidade:",
        db.list(
            "select id,name from defensoria_unit where municipality_id=? and active order by name",
            municipality),
        0);
  }

  private SchedulingService.SlotCursor cursor(tools.jackson.databind.JsonNode node) {
    if (!node.isObject()) return null;
    return new SchedulingService.SlotCursor(
        OffsetDateTime.parse(node.path("startsAt").asText()),
        UUID.fromString(node.path("id").asText()));
  }

  private String slots(String phone, UUID unit, String area, SchedulingService.SlotCursor after) {
    var page = scheduling.availablePage(unit, area, after);
    if (page.items().isEmpty() && after != null) {
      // Keep the last displayed options usable even if later rows were just reserved.
      return "Não há mais vagas após esta página. Escolha uma opção exibida ou envie MENU para"
          + " atualizar a consulta.";
    }
    var rows =
        page.items().stream()
            .map(s -> Map.<String, Object>of("id", s.get("id"), "name", date(s)))
            .toList();
    String answer = menu(phone, "Escolha uma vaga (horário de Fortaleza):", rows, 0);
    var options = new HashMap<String, Object>();
    options.put("ids", rows.stream().map(r -> r.get("id").toString()).toList());
    options.put("start", after);
    options.put("next", page.next());
    db.jdbc.update(
        "update whatsapp_conversation set options=? where phone=?",
        json.writeValueAsString(options),
        phone);
    if (rows.isEmpty())
      return "Não há vagas disponíveis. "
          + contact(Map.of("unit_id", unit))
          + " Envie MENU para outra consulta.";
    return answer
        + (page.next() != null ? "\nEnvie MAIS para outras vagas." : "\nÚltima página de vagas.");
  }

  private String menu(String phone, String title, List<Map<String, Object>> rows, int offset) {
    var ids = rows.stream().map(r -> r.get("id").toString()).toList();
    db.jdbc.update(
        "update whatsapp_conversation set options=? where phone=?",
        json.writeValueAsString(Map.of("ids", ids, "offset", offset)),
        phone);
    var out = new StringBuilder(title);
    for (int i = 0; i < rows.size(); i++)
      out.append("\n").append(i + 1).append(" — ").append(rows.get(i).get("name"));
    if (rows.isEmpty()) out.append("\nNenhuma opção cadastrada. Procure a Defensoria da região.");
    return out.toString();
  }

  private UUID choice(Map<String, Object> c, String text) {
    var ids = json.readTree((String) c.get("options")).path("ids");
    int n = Integer.parseInt(text);
    require(n > 0 && n <= ids.size(), "Opção inválida. Envie RETOMAR para consultar as opções.");
    return UUID.fromString(ids.get(n - 1).asText());
  }

  private String contact(Map<String, Object> c) {
    if (c.get("unit_id") == null) return "Procure o contato oficial da Defensoria da região.";
    var u = db.one("select name,phone from defensoria_unit where id=?", c.get("unit_id"));
    return "Contato da unidade " + u.get("name") + ": " + u.get("phone") + ".";
  }

  private String prompt(String phone, Map<String, Object> c) {
    return switch ((String) c.get("state")) {
      case "MUNICIPALITY" -> municipalities(phone);
      case "UNIT" -> units(phone, (UUID) c.get("municipality_id"));
      case "AREA" -> areas(phone, (UUID) c.get("unit_id"));
      case "SLOT" ->
          slots(
              phone,
              (UUID) c.get("unit_id"),
              (String) c.get("area"),
              cursor(json.readTree((String) c.get("options")).path("start")));
      case "FIRST_NAME" -> "Informe seu nome.";
      case "LAST_NAME" -> "Informe seu sobrenome.";
      case "CONFIRM" -> confirmation(c, (String) c.get("last_name"));
      default -> "Agendamento concluído. Consulte o protocolo enviado ou envie MENU.";
    };
  }
}
