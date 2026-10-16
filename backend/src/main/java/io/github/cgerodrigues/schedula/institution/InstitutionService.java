package io.github.cgerodrigues.schedula.institution;

import static io.github.cgerodrigues.schedula.shared.DomainException.require;

import io.github.cgerodrigues.schedula.shared.*;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstitutionService {
  private final Database db;
  private final PasswordEncoder passwords;

  public InstitutionService(Database db, PasswordEncoder passwords) {
    this.db = db;
    this.passwords = passwords;
  }

  public record Municipality(
      @NotBlank @Size(max = 120) String name,
      @Pattern(regexp = "[A-Z]{2}") @NotNull String stateCode) {}

  public record Defender(
      @NotBlank @Size(max = 120) String username, @NotBlank @Size(max = 150) String name) {}

  public record Affiliation(
      @NotNull UUID defenderId,
      @NotNull UUID unitId,
      @Pattern(regexp = "CIVEL|CRIMINAL") @NotNull String area) {}

  @Transactional
  public UUID municipality(Municipality r, String actor) {
    Brazil.state(r.stateCode());
    db.lock("catalog:" + r.stateCode());
    var id = UUID.randomUUID();
    db.jdbc.update(
        "insert into municipality(id,name,state_code) values (?,?,?)",
        id,
        r.name().strip(),
        r.stateCode());
    db.audit(actor, "MUNICIPALITY_CREATED", id);
    return id;
  }

  @Transactional
  public UUID defender(Defender r, String actor) {
    var id = UUID.randomUUID();
    db.jdbc.update(
        "insert into staff_user(id,username,password_hash,name,role,status) values"
            + " (?,?,?,?,'DEFENSOR','PENDING_ACTIVATION')",
        id,
        r.username().strip(),
        passwords.encode(UUID.randomUUID().toString()),
        r.name());
    db.audit(actor, "DEFENDER_CREATED", id);
    return id;
  }

  @Transactional
  public UUID affiliation(Affiliation r, String actor) {
    require(
        db.exists(
            "select exists(select 1 from staff_user where id=? and role='DEFENSOR')",
            r.defenderId()),
        "Usuário deve ser defensor");
    db.lock("unit:" + r.unitId());
    require(
        db.exists(
            "select exists(select 1 from unit_area where unit_id=? and area=?)",
            r.unitId(),
            r.area()),
        "Área não oferecida pela unidade. Configure as áreas institucionais primeiro.");
    var id = UUID.randomUUID();
    db.jdbc.update(
        "insert into affiliation values (?,?,?,?)", id, r.defenderId(), r.unitId(), r.area());
    db.audit(actor, "AFFILIATION_CREATED", id);
    return id;
  }

  public List<Map<String, Object>> municipalities() {
    return db.list("select * from municipality order by name");
  }

  public List<Map<String, Object>> units() {
    return db.list(
        "select u.*,m.name municipality,m.state_code,m.ibge_code,coalesce((select"
            + " string_agg(area,',' order by area) from unit_area where unit_id=u.id),'') areas"
            + " from defensoria_unit u join municipality m on m.id=u.municipality_id order by"
            + " u.name");
  }

  private static final String UNIT_FROM =
      " from defensoria_unit u join municipality m on m.id=u.municipality_id";
  private static final String UNIT_SELECT =
      "select u.*,m.name municipality,m.state_code,m.ibge_code,coalesce((select"
          + " string_agg(area,',' order by area) from unit_area where unit_id=u.id),'') areas";

  public Map<String, Object> unit(UUID id) {
    return db.one(UNIT_SELECT + UNIT_FROM + " where u.id=?", id);
  }

  public record UnitPage(
      List<Map<String, Object>> items, long total, int page, int size, long totalPages) {}

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public UnitPage searchUnits(
      String name, String municipality, String area, Boolean active, int page, int size) {
    require(page >= 1 && size >= 1 && size <= 100, "Página ou tamanho inválido.");
    require(area.isEmpty() || Set.of("CIVEL", "CRIMINAL").contains(area), "Área inválida.");
    String where =
        " where position(lower(?) in lower(u.name))>0"
            + " and position(lower(?) in lower(m.name))>0";
    var args = new ArrayList<Object>(List.of(name.trim(), municipality.trim()));
    if (!area.isEmpty()) {
      where += " and exists(select 1 from unit_area a where a.unit_id=u.id and a.area=?)";
      args.add(area);
    }
    if (active != null) {
      where += " and u.active=?";
      args.add(active);
    }
    long total =
        db.jdbc.queryForObject("select count(*)" + UNIT_FROM + where, Long.class, args.toArray());
    long pages = Math.max(1, (total + size - 1) / size);
    int current = (int) Math.min(page, pages);
    args.add(size);
    args.add((long) (current - 1) * size);
    var items =
        db.list(
            UNIT_SELECT + UNIT_FROM + where + " order by lower(u.name),u.id limit ? offset ?",
            args.toArray());
    return new UnitPage(items, total, current, size, pages);
  }

  public List<Map<String, Object>> defenders() {
    return db.list(
        "select id,username,name,status from staff_user where role='DEFENSOR' order by name");
  }

  public List<Map<String, Object>> affiliations(UUID owner) {
    return db.list(
        "select a.*,u.name unit_name,s.name defender_name from affiliation a join defensoria_unit u"
            + " on u.id=a.unit_id join staff_user s on s.id=a.defender_id where (?::uuid is null or"
            + " a.defender_id=?::uuid) order by u.name",
        owner,
        owner);
  }

  @Transactional
  public void removeAffiliation(UUID id, String actor) {
    require(
        !db.exists("select exists(select 1 from slot where affiliation_id=?)", id),
        "Vínculo possui histórico de agenda e deve ser preservado");
    require(
        db.jdbc.update("delete from affiliation where id=?", id) == 1, "Vínculo não encontrado");
    db.audit(actor, "AFFILIATION_REMOVED", id);
  }

  public UUID staff(String username) {
    return (UUID) db.one("select id from staff_user where username=?", username).get("id");
  }
}
