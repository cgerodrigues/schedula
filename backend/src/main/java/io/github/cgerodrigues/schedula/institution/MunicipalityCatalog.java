package io.github.cgerodrigues.schedula.institution;

import static io.github.cgerodrigues.schedula.shared.DomainException.require;

import io.github.cgerodrigues.schedula.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MunicipalityCatalog {
  private final Database db;
  private final IbgeClient client;
  private final TransactionTemplate tx;

  public MunicipalityCatalog(Database db, IbgeClient client, PlatformTransactionManager manager) {
    this.db = db;
    this.client = client;
    this.tx = new TransactionTemplate(manager);
  }

  public record Catalog(
      List<Map<String, Object>> municipalities,
      boolean complete,
      Object syncedAt,
      String warning) {}

  public Catalog list(String uf, boolean refresh) {
    Brazil.state(uf);
    if (!refresh && complete(uf)) return local(uf, "");
    List<IbgeClient.City> cities;
    try {
      cities = client.municipalities(uf);
    } catch (DomainException e) {
      var local =
          local(
              uf,
              "IBGE indisponível. Exibindo somente municípios oficiais já armazenados; o catálogo"
                  + " pode estar incompleto.");
      if (local.municipalities().isEmpty())
        throw new DomainException(
            "IBGE indisponível e não há catálogo oficial local para esta UF. Tente novamente.");
      return local;
    }
    tx.executeWithoutResult(status -> synchronize(uf, cities));
    return local(uf, "");
  }

  private boolean complete(String uf) {
    return db.exists("select exists(select 1 from municipality_catalog where state_code=?)", uf);
  }

  private Catalog local(String uf, String warning) {
    var status = db.list("select synced_at from municipality_catalog where state_code=?", uf);
    var rows =
        new ArrayList<>(
            db.list("select * from municipality where state_code=? and ibge_code is not null", uf));
    rows.sort(Comparator.comparing(r -> Brazil.key((String) r.get("name"))));
    return new Catalog(
        rows,
        !status.isEmpty(),
        status.isEmpty() ? null : status.getFirst().get("synced_at"),
        warning);
  }

  private void synchronize(String uf, List<IbgeClient.City> cities) {
    db.lock("catalog:" + uf);
    require(cities != null && !cities.isEmpty(), "Catálogo IBGE vazio; sincronização não aplicada");
    Set<String> codes = new HashSet<>(), names = new HashSet<>();
    for (var city : cities) {
      require(
          uf.equals(city.stateCode())
              && city.code().matches("[0-9]{7}")
              && !Brazil.clean(city.name()).isBlank()
              && city.name().length() <= 120
              && codes.add(city.code())
              && names.add(Brazil.key(city.name())),
          "Catálogo IBGE inconsistente; sincronização não aplicada");
      var byCode = db.list("select * from municipality where ibge_code=?", city.code());
      var byName =
          db.list("select * from municipality where state_code=?", uf).stream()
              .filter(m -> Brazil.key((String) m.get("name")).equals(Brazil.key(city.name())))
              .toList();
      require(
          byName.size() <= 1,
          "Município ambíguo: " + city.name() + ". Regularize os registros antes de sincronizar.");
      if (!byCode.isEmpty()) {
        var existing = byCode.getFirst();
        require(
            uf.equals(existing.get("state_code"))
                && Brazil.key(city.name()).equals(Brazil.key((String) existing.get("name")))
                && (byName.isEmpty() || byName.getFirst().get("id").equals(existing.get("id"))),
            "Código IBGE conflitante: " + city.name() + ". Regularize o cadastro.");
      } else if (!byName.isEmpty()) {
        var legacy = byName.getFirst();
        require(
            legacy.get("ibge_code") == null, "Município possui outro código IBGE: " + city.name());
        db.jdbc.update(
            "update municipality set ibge_code=? where id=?", city.code(), legacy.get("id"));
      } else {
        db.jdbc.update(
            "insert into municipality(id,name,state_code,ibge_code) values (?,?,?,?)",
            UUID.randomUUID(),
            Brazil.clean(city.name()),
            uf,
            city.code());
      }
    }
    db.jdbc.update(
        "insert into municipality_catalog(state_code,synced_at,municipality_count) values"
            + " (?,now(),?) on conflict(state_code) do update set"
            + " synced_at=excluded.synced_at,municipality_count=excluded.municipality_count",
        uf,
        cities.size());
  }
}
