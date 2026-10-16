package io.github.cgerodrigues.schedula;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.cgerodrigues.schedula.institution.*;
import io.github.cgerodrigues.schedula.shared.DomainException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class CatalogIntegrationTest extends IntegrationBase {
  @MockitoBean IbgeClient ibge;
  @Autowired MunicipalityCatalog catalog;

  List<IbgeClient.City> cities() {
    return List.of(
        new IbgeClient.City("2304400", "Fortaleza", "CE"),
        new IbgeClient.City("2303709", "Caucaia", "CE"));
  }

  @Test
  void statesAreCompleteUniqueAndAlphabetical() {
    assertEquals(27, Brazil.STATES.size());
    assertEquals(27, Brazil.STATES.stream().map(Brazil.State::code).distinct().count());
    var names = Brazil.STATES.stream().map(s -> Brazil.key(s.name())).toList();
    assertEquals(names.stream().sorted().toList(), names);
  }

  @Test
  void reconcilesLegacyUuidAndLinksThenReusesCompleteLocalCatalog() {
    db.jdbc.update(
        "update municipality set name='  Fortaléza  ',ibge_code=null where id=?", municipality);
    when(ibge.municipalities("CE")).thenReturn(cities());
    var first = catalog.list("CE", false);
    assertTrue(first.complete());
    assertNotNull(first.syncedAt());
    assertEquals("Caucaia", first.municipalities().getFirst().get("name"));
    assertEquals(
        municipality, db.one("select id from municipality where ibge_code='2304400'").get("id"));
    assertEquals(
        municipality,
        db.one("select municipality_id from defensoria_unit where id=?", unit)
            .get("municipality_id"));
    catalog.list("CE", false);
    verify(ibge, times(1)).municipalities("CE");
    catalog.list("CE", true);
    assertEquals(2, db.jdbc.queryForObject("select count(*) from municipality", Integer.class));
  }

  @Test
  void automaticallyImportsAnAbsentStateThenReusesItWithoutChangingExistingMunicipalities() {
    var existing = db.list("select * from municipality order by id");
    when(ibge.municipalities("PE"))
        .thenReturn(List.of(new IbgeClient.City("2611606", "Recife", "PE")));
    var first = catalog.list("PE", false);
    assertTrue(first.complete());
    assertEquals("Recife", first.municipalities().getFirst().get("name"));
    var second = catalog.list("PE", false);
    assertEquals(first.municipalities(), second.municipalities());
    verify(ibge, times(1)).municipalities("PE");
    assertEquals(existing, db.list("select * from municipality where state_code='CE' order by id"));
    assertEquals(1, db.jdbc.queryForObject(
        "select count(*) from municipality where state_code='PE'", Integer.class));
  }

  @Test
  void partialCatalogDoesNotCountAsSyncedAndOfflineFallbackIsExplicit() {
    when(ibge.municipalities("CE")).thenThrow(new DomainException("offline"));
    var result = catalog.list("CE", false);
    assertFalse(result.complete());
    assertFalse(result.warning().isBlank());
    assertEquals(1, result.municipalities().size());
    db.jdbc.update("update municipality set ibge_code=null");
    assertThrows(DomainException.class, () -> catalog.list("CE", false));
    verify(ibge, times(2)).municipalities("CE");
  }

  @Test
  void failedRefreshKeepsCompleteCatalog() {
    db.jdbc.update("update municipality set name='Fortaleza' where id=?", municipality);
    when(ibge.municipalities("CE")).thenReturn(cities());
    catalog.list("CE", false);
    when(ibge.municipalities("CE")).thenThrow(new DomainException("offline"));
    assertTrue(catalog.list("CE", true).complete());
    assertEquals(2, catalog.list("CE", false).municipalities().size());
  }

  @Test
  void ambiguousLegacyMatchRollsBackAllChanges() {
    db.jdbc.update(
        "update municipality set name='Fortaleza',ibge_code=null where id=?", municipality);
    db.jdbc.update(
        "insert into municipality(id,name,state_code) values (?,'FORTALEZA','CE')",
        UUID.randomUUID());
    when(ibge.municipalities("CE")).thenReturn(List.of(cities().getLast(), cities().getFirst()));
    assertThrows(DomainException.class, () -> catalog.list("CE", false));
    assertEquals(
        0,
        db.jdbc.queryForObject(
            "select count(*) from municipality where ibge_code is not null", Integer.class));
    assertEquals(
        0, db.jdbc.queryForObject("select count(*) from municipality_catalog", Integer.class));
    assertEquals(2, db.jdbc.queryForObject("select count(*) from municipality", Integer.class));
  }

  @Test
  void codeConflictIsNotSilentlyOverwritten() {
    when(ibge.municipalities("CE")).thenReturn(cities());
    assertThrows(DomainException.class, () -> catalog.list("CE", false));
    assertEquals(
        "Município Exemplo",
        db.one("select name from municipality where id=?", municipality).get("name"));
  }

  @Test
  void rejectsWrongUfEmptyAndDuplicateResponses() {
    assertThrows(DomainException.class, () -> catalog.list("XX", false));
    when(ibge.municipalities("CE")).thenReturn(List.of());
    assertThrows(DomainException.class, () -> catalog.list("CE", false));
    when(ibge.municipalities("CE"))
        .thenReturn(List.of(new IbgeClient.City("2611606", "Recife", "PE")));
    assertThrows(DomainException.class, () -> catalog.list("CE", false));
    db.jdbc.update("update municipality set name='Fortaleza' where id=?", municipality);
    when(ibge.municipalities("CE")).thenReturn(List.of(cities().getFirst(), cities().getFirst()));
    assertThrows(DomainException.class, () -> catalog.list("CE", false));
    assertEquals(
        0, db.jdbc.queryForObject("select count(*) from municipality_catalog", Integer.class));
  }

  @Test
  void simultaneousSyncIsIdempotent() throws Exception {
    db.jdbc.update(
        "update municipality set name='Fortaleza',ibge_code=null where id=?", municipality);
    when(ibge.municipalities("CE")).thenReturn(cities());
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> catalog.list("CE", true));
      var b = pool.submit(() -> catalog.list("CE", true));
      assertTrue(a.get().complete());
      assertTrue(b.get().complete());
    }
    assertEquals(2, db.jdbc.queryForObject("select count(*) from municipality", Integer.class));
  }
}
