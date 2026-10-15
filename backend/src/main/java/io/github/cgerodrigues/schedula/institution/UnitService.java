package io.github.cgerodrigues.schedula.institution;

import static io.github.cgerodrigues.schedula.shared.DomainException.require;

import io.github.cgerodrigues.schedula.shared.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnitService {
  private final Database db;
  private final Clock clock;

  public UnitService(Database db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public record Input(
      @NotNull UUID municipalityId,
      @NotBlank String stateCode,
      @NotBlank @Size(max = 150) String name,
      @NotNull @Size(min = 1, max = 2) Set<@Pattern(regexp = "CIVEL|CRIMINAL") String> areas,
      @NotBlank @Size(max = 20) String phone,
      @Email @Size(max = 254) String email,
      @NotBlank @Pattern(regexp = "[0-9]{5}-?[0-9]{3}") String postalCode,
      @NotBlank @Size(max = 150) String street,
      @NotBlank @Size(max = 20) String streetNumber,
      @Size(max = 100) String complement,
      @NotBlank @Size(max = 100) String neighborhood,
      Boolean active) {
    public Input {
      if (active == null) active = true;
    }
  }

  @Transactional
  public UUID save(UUID id, Input r, String actor) {
    Brazil.state(r.stateCode());
    String name = Brazil.clean(r.name());
    require(
        !name.isEmpty() && name.length() <= 150, "Informe o nome da unidade (até 150 caracteres)");
    require(
        r.areas() != null
            && !r.areas().isEmpty()
            && Set.of("CIVEL", "CRIMINAL").containsAll(r.areas()),
        "Selecione ao menos uma área válida");
    require(
        db.exists(
            "select exists(select 1 from municipality where id=? and state_code=? and ibge_code is"
                + " not null)",
            r.municipalityId(),
            r.stateCode()),
        "Selecione município oficial compatível com a UF; sincronize o catálogo para regularizar"
            + " cadastros antigos");
    boolean creating = id == null;
    if (creating) id = UUID.randomUUID();
    db.lock("unit:" + id);
    Map<String, Object> old =
        creating ? Map.of() : db.one("select * from defensoria_unit where id=?", id);
    String phone =
        Objects.equals(r.phone(), old.get("phone")) ? r.phone() : Brazil.phone(r.phone());
    if (!creating) {
      if (!Objects.equals(old.get("municipality_id"), r.municipalityId()))
        require(
            !db.exists("select exists(select 1 from affiliation where unit_id=?)", id),
            "Unidade possui vínculos ou histórico. Regularize a estrutura institucional antes de"
                + " mudar o município.");
      if (!r.active())
        require(
            !hasAgenda(id, null),
            "Não é possível desativar: existem disponibilidades vigentes/futuras ou atendimentos"
                + " confirmados. Regularize a agenda primeiro.");
      for (var row : db.list("select area from unit_area where unit_id=?", id)) {
        String area = (String) row.get("area");
        if (!r.areas().contains(area)) {
          require(
              !hasAgenda(id, area),
              "Não é possível remover a área "
                  + area
                  + " porque existem atendimentos ou disponibilidades futuras vinculadas a essa"
                  + " área.");
          require(
              !db.exists(
                  "select exists(select 1 from affiliation where unit_id=? and area=?)", id, area),
              "Não é possível remover a área "
                  + area
                  + ": regularize os vínculos de defensores primeiro.");
        }
      }
    }
    require(
        !db.exists(
            "select exists(select 1 from defensoria_unit where municipality_id=? and"
                + " lower(regexp_replace(btrim(name), '\\s+', ' ', 'g'))=lower(?) and id<>?)",
            r.municipalityId(),
            name,
            id),
        "Já existe unidade com este nome no município");
    String street = Brazil.clean(r.street()),
        number = Brazil.clean(r.streetNumber()),
        neighborhood = Brazil.clean(r.neighborhood());
    require(
        !street.isEmpty() && !number.isEmpty() && !neighborhood.isEmpty(),
        "Informe logradouro, número e bairro");
    require(
        r.postalCode() != null && r.postalCode().matches("[0-9]{5}-?[0-9]{3}"),
        "CEP deve conter oito dígitos");
    // Keep the original free-form address permanently; structured fields are displayed
    // preferentially.
    String address = street + ", " + number + " - " + neighborhood;
    if (address.length() > 255) address = address.substring(0, 255);
    if (creating)
      db.jdbc.update(
          "insert into defensoria_unit(id,municipality_id,name,address,phone) values (?,?,?,?,?)",
          id,
          r.municipalityId(),
          name,
          address,
          phone);
    db.jdbc.update(
        "update defensoria_unit set"
            + " municipality_id=?,name=?,phone=?,email=?,postal_code=?,street=?,street_number=?,complement=?,neighborhood=?,active=?"
            + " where id=?",
        r.municipalityId(),
        name,
        phone,
        Brazil.clean(r.email()),
        r.postalCode().replace("-", ""),
        street,
        number,
        Brazil.clean(r.complement()),
        neighborhood,
        r.active(),
        id);
    db.jdbc.update(
        "delete from unit_area where unit_id=? and area not in (select unnest(?::text[]))",
        id,
        r.areas().toArray(String[]::new));
    for (String area : r.areas())
      db.jdbc.update("insert into unit_area values (?,?) on conflict do nothing", id, area);
    db.audit(actor, creating ? "UNIT_CREATED" : "UNIT_UPDATED", id);
    return id;
  }

  private boolean hasAgenda(UUID id, String area) {
    return db.exists(
        "select exists(select 1 from affiliation f join slot s on s.affiliation_id=f.id where"
            + " f.unit_id=? and (?::text is null or f.area=?) and ((s.published and s.ends_at>?) or"
            + " exists(select 1 from appointment a where a.slot_id=s.id and"
            + " a.status='CONFIRMADO')))",
        id,
        area,
        area,
        OffsetDateTime.now(clock));
  }
}
