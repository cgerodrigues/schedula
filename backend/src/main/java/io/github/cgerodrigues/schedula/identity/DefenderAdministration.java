package io.github.cgerodrigues.schedula.identity;

import static io.github.cgerodrigues.schedula.shared.DomainException.require;

import io.github.cgerodrigues.schedula.shared.Database;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DefenderAdministration {
  private final Database db;
  private final Clock clock;

  public DefenderAdministration(Database db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  private static final String SELECT =
      "select s.id,s.name,s.username,s.status,"
          + " case when i.id is null then 'NONE' when i.used_at is not null then 'USED'"
          + " when i.revoked_at is not null then 'REVOKED' when i.expires_at<=? then 'EXPIRED'"
          + " else 'AVAILABLE' end invitation_status,i.expires_at invitation_expires_at"
          + " from staff_user s left join lateral (select id,used_at,revoked_at,expires_at"
          + " from account_invitation where account_id=s.id"
          + " order by (used_at is null and revoked_at is null) desc,"
          + " (used_at is not null) desc,created_at desc,id desc limit 1) i on true";

  public Map<String, Object> indicators() {
    return db.one("select count(*) total from staff_user where role='DEFENSOR'");
  }

  public Map<String, Object> get(UUID id) {
    return db.one(SELECT + " where s.id=? and s.role='DEFENSOR'", OffsetDateTime.now(clock), id);
  }

  public record Page(
      List<Map<String, Object>> items, long total, int page, int size, long totalPages) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page search(String name, String status, int page, int size) {
    require(page >= 1 && size >= 1 && size <= 100, "Página ou tamanho inválido.");
    require(
        status.isEmpty() || Set.of("ACTIVE", "PENDING_ACTIVATION").contains(status),
        "Situação inválida.");
    String where = " where s.role='DEFENSOR' and position(regexp_replace(normalize(lower(?), NFD), '[̀-ͯ]', '', 'g') in regexp_replace(normalize(lower(s.name), NFD), '[̀-ͯ]', '', 'g'))>0";
    var args = new ArrayList<Object>(List.of(name.strip()));
    if (!status.isEmpty()) {
      where += " and s.status=?";
      args.add(status);
    }
    long total =
        db.jdbc.queryForObject(
            "select count(*) from staff_user s" + where, Long.class, args.toArray());
    long pages = Math.max(1, (total + size - 1) / size);
    int current = (int) Math.min(page, pages);
    args.addFirst(OffsetDateTime.now(clock));
    args.add(size);
    args.add((long) (current - 1) * size);
    var items = db.list(
        SELECT + where + " order by lower(s.name),s.id limit ? offset ?", args.toArray());
    return new Page(items, total, current, size, pages);
  }

  @Transactional
  public void edit(UUID id, String name, String actor) {
    db.one("select id from staff_user where id=? and role='DEFENSOR' for update", id);
    db.jdbc.update("update staff_user set name=? where id=?", name.strip(), id);
    db.audit(actor, "DEFENDER_UPDATED", id);
  }
}
