package io.github.cgerodrigues.schedula;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cgerodrigues.schedula.identity.*;
import io.github.cgerodrigues.schedula.institution.InstitutionService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class DefenderAdministrationTest extends IntegrationBase {
  @Autowired DefenderAdministration administration;
  @Autowired MockMvc mvc;

  @Test
  void searchesAccentsWithoutChangingNames() {
    var id=institution.defender(new InstitutionService.Defender("accent", "Laís Conceição"),"rh");
    assertEquals(id,administration.search("LAIS CONCEICAO","",1,10).items().getFirst().get("id"));
    assertEquals(1,administration.search("Laís Conceição","",1,10).total());
    assertEquals("Laís Conceição",administration.get(id).get("name"));
  }

  @Test
  void aggregateCountsAllDefendersWithoutReturningAccounts() throws Exception {
    mvc.perform(get("/api/rh/defenders/indicators").with(user("rh").roles("RH")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items").doesNotExist());
    institution.defender(new InstitutionService.Defender("new-pending", "Pendente"),"rh");
    assertEquals(3L, administration.indicators().get("total"));
    mvc.perform(get("/api/rh/defenders/indicators")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/rh/defenders/indicators").with(user("defensor").roles("DEFENSOR")))
        .andExpect(status().isForbidden());
  }

  @Test
  void refusesActiveAccountRevocationWithoutChangingCredentialsOrLogin() throws Exception {
    var before = db.one("select * from staff_user where id=?", defender);
    mvc.perform(delete("/api/rh/defenders/"+defender+"/invitation")
        .with(user("rh").roles("RH")).with(csrf())).andExpect(status().isConflict());
    assertEquals(before, db.one("select * from staff_user where id=?", defender));
    mvc.perform(post("/api/auth/login").with(csrf()).param("username","defensor").param("password","test-only-password"))
        .andExpect(status().isNoContent());
    var id = institution.defender(new InstitutionService.Defender("activated", "Ativado"),"rh");
    var invite = accounts.invite(id,"rh");
    accounts.activate(invite.token(),"personal-test-password","personal-test-password");
    var history = db.list("select * from account_invitation where account_id=?",id);
    assertThrows(io.github.cgerodrigues.schedula.shared.DomainException.class, () -> accounts.revoke(id,"rh"));
    assertEquals(history, db.list("select * from account_invitation where account_id=?",id));
  }

  @Test
  void filtersWholeDatasetAndPaginatesWithStableTies() {
    for (int i=0; i<23; i++) db.jdbc.update(
        "insert into staff_user(id,username,name,password_hash,role,status) values (?,?,?,'unused','DEFENSOR','PENDING_ACTIVATION')",
        UUID.randomUUID(), "search-"+i, "Mesmo Nome");
    var first = administration.search("MESMO", "PENDING_ACTIVATION", 1, 10);
    var next = administration.search("mesmo", "PENDING_ACTIVATION", 2, 10);
    var last = administration.search("mesmo", "PENDING_ACTIVATION", 3, 10);
    assertEquals(23, first.total()); assertEquals(3, first.totalPages());
    assertEquals(10, first.items().size()); assertEquals(3,last.items().size());
    Set<Object> ids=new HashSet<>();
    for(var page:List.of(first,next,last)) for(var row:page.items()) assertTrue(ids.add(row.get("id")));
    assertEquals(first.items(), administration.search("mesmo", "PENDING_ACTIVATION",1,10).items());
    assertEquals(0,administration.search("mesmo","ACTIVE",1,10).total());
    assertEquals(2,administration.search("","ACTIVE",1,10).total());
    assertEquals(1,administration.search("ausente","",99,10).page());
    assertEquals(3,administration.search("mesmo","",99,10).page());
  }

  @Test
  void editsOnlyNameAndKeepsExistingCredentialsAndRelationships() throws Exception {
    var before=db.one("select * from staff_user where id=?",defender);
    var links=db.list("select * from affiliation where defender_id=? order by id",defender);
    mvc.perform(put("/api/rh/defenders/"+defender).with(user("rh").roles("RH")).with(csrf())
        .contentType("application/json").content("{\"name\":\"Nome corrigido\"}"))
        .andExpect(status().isOk());
    var after=db.one("select * from staff_user where id=?",defender);
    assertEquals("Nome corrigido",after.get("name"));
    after.put("name",before.get("name")); assertEquals(before,after);
    assertEquals(links,db.list("select * from affiliation where defender_id=? order by id",defender));
    mvc.perform(post("/api/auth/login").with(csrf()).param("username","defensor").param("password","test-only-password"))
        .andExpect(status().isNoContent());
  }

  @Test
  void rejectsCredentialAndPrivilegeInjectionWithoutChangingTheAccount() throws Exception {
    var before=db.one("select * from staff_user where id=?",defender);
    for(String field:List.of("username","password","password_hash","passwordHash","confirmation","role","status")) {
      mvc.perform(put("/api/rh/defenders/"+defender).with(user("rh").roles("RH")).with(csrf())
          .contentType("application/json").content("{\"name\":\"Outro nome\",\""+field+"\":\"injected-value\"}"))
          .andExpect(status().isBadRequest());
    }
    assertEquals(before,db.one("select * from staff_user where id=?",defender));
    mvc.perform(post("/api/rh/defenders").with(user("rh").roles("RH")).with(csrf())
        .contentType("application/json").content("{\"name\":\"Novo\",\"username\":\"new-user\",\"password\":\"injected-password\"}"))
        .andExpect(status().isBadRequest());
    assertFalse(db.exists("select exists(select 1 from staff_user where username='new-user')"));
  }

  @Test
  void requiresRhAndCsrfAndKeepsLegacyListContract() throws Exception {
    for(String path:List.of("/api/rh/defenders/search","/api/rh/defenders/"+defender)) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(get(path).with(user("defensor").roles("DEFENSOR"))).andExpect(status().isForbidden());
    }
    mvc.perform(put("/api/rh/defenders/"+defender).with(user("defensor").roles("DEFENSOR")).with(csrf())
        .contentType("application/json").content("{\"name\":\"Nome\"}")).andExpect(status().isForbidden());
    mvc.perform(put("/api/rh/defenders/"+defender).with(user("rh").roles("RH"))
        .contentType("application/json").content("{\"name\":\"Nome\"}")).andExpect(status().isForbidden());
    mvc.perform(get("/api/rh/defenders/"+defender).with(user("rh").roles("RH")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("defensor"))
        .andExpect(jsonPath("$.password_hash").doesNotExist());
    mvc.perform(get("/api/rh/defenders").with(user("rh").roles("RH")))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").exists());
    mvc.perform(get("/api/rh/defenders/"+UUID.randomUUID()).with(user("rh").roles("RH")))
        .andExpect(status().isNotFound());
  }

  @Test
  void distinguishesAccountAndLatestInvitationEvenWithIdenticalIssuanceTimes() {
    var id=institution.defender(new InstitutionService.Defender("pending", "Pendente"),"rh");
    assertEquals("NONE",administration.get(id).get("invitation_status"));
    accounts.invite(id,"rh"); var current=accounts.invite(id,"rh");
    assertEquals("AVAILABLE",administration.get(id).get("invitation_status"));
    assertEquals("PENDING_ACTIVATION",administration.get(id).get("status"));
    accounts.revoke(id,"rh"); assertEquals("REVOKED",administration.get(id).get("invitation_status"));
    current=accounts.invite(id,"rh");
    db.jdbc.update("update account_invitation set created_at=created_at-interval '2 days',expires_at=expires_at-interval '2 days' where account_id=? and revoked_at is null",id);
    assertEquals("EXPIRED",administration.get(id).get("invitation_status"));
    current=accounts.invite(id,"rh");
    accounts.activate(current.token(),"personal-test-password","personal-test-password");
    assertEquals("ACTIVE",administration.get(id).get("status"));
    assertEquals("USED",administration.get(id).get("invitation_status"));
    assertThrows(io.github.cgerodrigues.schedula.shared.DomainException.class,()->accounts.invite(id,"rh"));
  }

  @Test
  void exposesOnlyIdentityForValidTokenAndNeverForUnavailableInvitations() throws Exception {
    var id=institution.defender(new InstitutionService.Defender("pending", "Nome do defensor"),"rh");
    var invite=accounts.invite(id,"rh");
    mvc.perform(post("/api/auth/activation/check").with(csrf()).contentType("application/json")
        .content("{\"token\":\""+invite.token()+"\"}"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(jsonPath("$.name").value("Nome do defensor"))
        .andExpect(jsonPath("$.username").value("pending"))
        .andExpect(jsonPath("$.password_hash").doesNotExist()).andExpect(jsonPath("$.id").doesNotExist());
    accounts.revoke(id,"rh");
    for(String token:List.of(invite.token(),"x".repeat(43)))
      mvc.perform(post("/api/auth/activation/check").with(csrf()).contentType("application/json")
          .content("{\"token\":\""+token+"\"}"))
          .andExpect(status().isConflict()).andExpect(jsonPath("$.name").doesNotExist())
          .andExpect(jsonPath("$.username").doesNotExist());
    var expired=accounts.invite(id,"rh");
    db.jdbc.update("update account_invitation set created_at=created_at-interval '2 days',expires_at=expires_at-interval '2 days' where account_id=?",id);
    mvc.perform(post("/api/auth/activation/check").with(csrf()).contentType("application/json")
        .content("{\"token\":\""+expired.token()+"\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.name").doesNotExist());
  }

  @Test
  void passwordLimitsCountCodePointsAndUtf8Bytes() {
    assertThrows(io.github.cgerodrigues.schedula.shared.DomainException.class,()->AccountService.validatePassword("😀".repeat(6)));
    assertDoesNotThrow(()->AccountService.validatePassword("😀".repeat(18)));
    assertThrows(io.github.cgerodrigues.schedula.shared.DomainException.class,()->AccountService.validatePassword("😀".repeat(19)));
    assertDoesNotThrow(()->AccountService.validatePassword("é".repeat(36)));
    assertThrows(io.github.cgerodrigues.schedula.shared.DomainException.class,()->AccountService.validatePassword("é".repeat(37)));
  }
}
