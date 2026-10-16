package io.github.cgerodrigues.schedula;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cgerodrigues.schedula.shared.DomainException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class UnitSearchIntegrationTest extends IntegrationBase {
  @Autowired MockMvc mvc;

  @Test
  void combinedFiltersApplyToEntireDatasetAndDoNotDuplicateAreas() {
    for (int i = 0; i < 24; i++) units.save(null, unitInput(municipality, "Busca " + String.format("%02d", i)), "test");
    db.jdbc.update("update defensoria_unit set active=false where name='Busca 23'");
    var first = institution.searchUnits("busca", "EXEMPLO", "CIVEL", true, 1, 10);
    var second = institution.searchUnits("busca", "EXEMPLO", "CIVEL", true, 2, 10);
    var last = institution.searchUnits("busca", "EXEMPLO", "CIVEL", true, 3, 10);
    assertEquals(23, first.total());
    assertEquals(3, first.totalPages());
    assertEquals("Busca 00", first.items().getFirst().get("name"));
    assertEquals("Busca 10", second.items().getFirst().get("name"));
    assertEquals(3, last.items().size());
    var ids = new HashSet<Object>();
    for (var page : List.of(first, second, last)) for (var row : page.items()) assertTrue(ids.add(row.get("id")));
    assertEquals(1, institution.searchUnits("busca", "EXEMPLO", "CRIMINAL", false, 1, 10).total());
    assertEquals(0, institution.searchUnits("busca", "outra cidade", "", null, 1, 10).total());
  }

  @Test
  void deterministicTiesClampPageAndTreatSearchAsLiteralText() {
    var city = UUID.randomUUID();
    db.jdbc.update("insert into municipality(id,name,state_code,ibge_code) values (?,'Outra cidade','CE','2303709')", city);
    units.save(null, unitInput(city, "Unidade Demonstração"), "test");
    var all = institution.searchUnits("", "", "", null, 1, 10);
    assertEquals(2, all.total());
    assertEquals(all.items().get(0).get("id"), institution.searchUnits("", "", "", null, 1, 1).items().getFirst().get("id"));
    assertEquals(all.items().get(1).get("id"), institution.searchUnits("", "", "", null, 2, 1).items().getFirst().get("id"));
    assertEquals(1, institution.searchUnits("", "", "", null, 999, 10).page());
    assertEquals(0, institution.searchUnits("%", "", "", null, 1, 10).total());
    assertThrows(DomainException.class, () -> institution.searchUnits("", "", "", null, 0, 10));
    assertThrows(DomainException.class, () -> institution.searchUnits("", "", "", null, 1, 101));
    assertThrows(DomainException.class, () -> institution.searchUnits("", "", "INVALID", null, 1, 10));
  }

  @Test
  void searchAndDetailRequireRhAndLegacyArrayRemainsAvailable() throws Exception {
    mvc.perform(get("/api/rh/units/search")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/rh/units/" + unit)).andExpect(status().isUnauthorized());
    for (var path : List.of("/api/rh/units/search", "/api/rh/units/" + unit))
      mvc.perform(get(path).with(user("defensor").roles("DEFENSOR"))).andExpect(status().isForbidden());
    mvc.perform(get("/api/rh/units/search").with(user("rh").roles("RH")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].areas").value("CIVEL,CRIMINAL"));
    mvc.perform(get("/api/rh/units/" + unit).with(user("rh").roles("RH")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(unit.toString()));
    mvc.perform(get("/api/rh/units/" + UUID.randomUUID()).with(user("rh").roles("RH")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/rh/units").with(user("rh").roles("RH")))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(unit.toString()));
  }
}
