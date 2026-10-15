package io.github.cgerodrigues.schedula;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cgerodrigues.schedula.identity.*;
import io.github.cgerodrigues.schedula.institution.InstitutionService;
import io.github.cgerodrigues.schedula.shared.DomainException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AccountIntegrationTest extends IntegrationBase {
  @Autowired MockMvc mvc;
  @Autowired PasswordEncoder encoder;
  @Autowired BootstrapAdmin bootstrap;
  @Autowired PlatformTransactionManager manager;

  private UUID pending() {
    return institution.defender(
        new InstitutionService.Defender("pending", "Defensor Pendente"), "rh");
  }

  private MockHttpSession login(String user, String password, int expected) throws Exception {
    var result =
        mvc.perform(
                post("/api/auth/login")
                    .with(csrf())
                    .param("username", user)
                    .param("password", password))
            .andExpect(status().is(expected))
            .andReturn();
    return (MockHttpSession) result.getRequest().getSession(false);
  }

  @Test
  void bootstrapCreatesRhAndNeverOverwritesExistingPassword() throws Exception {
    ApplicationRunner runner =
        ReflectionTestUtils.invokeMethod(
            new BootstrapAdmin(),
            "bootstrap",
            db,
            encoder,
            "rh-bootstrap",
            "initial-test-password");
    runner.run(null);
    login("rh-bootstrap", "initial-test-password", 204);
    runner =
        ReflectionTestUtils.invokeMethod(
            new BootstrapAdmin(),
            "bootstrap",
            db,
            encoder,
            "rh-bootstrap",
            "different-test-password");
    runner.run(null);
    login("rh-bootstrap", "initial-test-password", 204);
    login("rh-bootstrap", "different-test-password", 401);
  }

  @Test
  void rhCreatesDefenderWithoutPasswordAndPendingCannotLogin() throws Exception {
    mvc.perform(
            post("/api/rh/defenders")
                .with(user("rh").roles("RH"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"pending\",\"name\":\"Defensor Pendente\"}"))
        .andExpect(status().isOk());
    assertEquals(
        "PENDING_ACTIVATION",
        db.one("select status from staff_user where username='pending'").get("status"));
    login("pending", "test-only-password", 401);
  }

  @Test
  void invitationActivatesExactlyItsAccountAndAllowsLogin() throws Exception {
    UUID id = pending();
    var invite = accounts.invite(id, "rh");
    accounts.check(invite.token());
    var row = db.one("select * from account_invitation where account_id=?", id);
    assertNotEquals(invite.token(), row.get("token_hash"));
    assertEquals(64, row.get("token_hash").toString().length());
    assertEquals(
        java.time.Duration.ofHours(24),
        java.time.Duration.between(
            io.github.cgerodrigues.schedula.appointment.AppointmentService.instant(
                row, "created_at"),
            invite.expiresAt().toInstant()));
    accounts.activate(invite.token(), "personal-test-password", "personal-test-password");
    login("pending", "personal-test-password", 204);
    assertTrue(
        encoder.matches(
            "personal-test-password",
            (String)
                db.one("select password_hash from staff_user where id=?", id)
                    .get("password_hash")));
    assertThrows(
        DomainException.class,
        () -> accounts.activate(invite.token(), "other-test-password", "other-test-password"));
  }

  @Test
  void expiredInvitationCannotActivate() {
    var id = pending();
    var invite = accounts.invite(id, "rh");
    db.jdbc.update(
        "update account_invitation set created_at=created_at-interval '2"
            + " days',expires_at=expires_at-interval '2 days' where account_id=?",
        id);
    assertThrows(
        DomainException.class,
        () ->
            accounts.activate(invite.token(), "personal-test-password", "personal-test-password"));
    assertEquals(
        "PENDING_ACTIVATION", db.one("select status from staff_user where id=?", id).get("status"));
  }

  @Test
  void replacementRevokesPreviousAndExplicitRevocationWorks() {
    var id = pending();
    var old = accounts.invite(id, "rh");
    var next = accounts.invite(id, "rh");
    assertThrows(DomainException.class, () -> accounts.check(old.token()));
    accounts.check(next.token());
    accounts.revoke(id, "rh");
    assertThrows(DomainException.class, () -> accounts.check(next.token()));
  }

  @Test
  void activationRollbackPreservesPendingAccountAndInvitation() {
    var id = pending();
    var invite = accounts.invite(id, "rh");
    new TransactionTemplate(manager)
        .executeWithoutResult(
            t -> {
              accounts.activate(invite.token(), "personal-test-password", "personal-test-password");
              t.setRollbackOnly();
            });
    accounts.check(invite.token());
    assertEquals(
        "PENDING_ACTIVATION", db.one("select status from staff_user where id=?", id).get("status"));
  }

  @Test
  void passwordValidationDoesNotConsumeInvitation() {
    var id = pending();
    var invite = accounts.invite(id, "rh");
    assertThrows(DomainException.class, () -> accounts.activate(invite.token(), "short", "short"));
    assertThrows(
        DomainException.class,
        () -> accounts.activate(invite.token(), "personal-test-password", "mismatch-password"));
    assertThrows(
        DomainException.class,
        () -> accounts.activate(invite.token(), "é".repeat(40), "é".repeat(40)));
    accounts.check(invite.token());
  }

  @Test
  void concurrentConsumptionHasOneWinner() throws Exception {
    var id = pending();
    var invite = accounts.invite(id, "rh");
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 2; i++)
        tasks.add(
            executor.submit(
                () -> {
                  try {
                    accounts.activate(
                        invite.token(), "personal-test-password", "personal-test-password");
                    return true;
                  } catch (DomainException e) {
                    return false;
                  }
                }));
      int count = 0;
      for (var task : tasks) if (task.get()) count++;
      assertEquals(1, count);
    }
  }

  @Test
  void changePasswordRequiresCurrentAndInvalidatesSession() throws Exception {
    var session = login("defensor", "test-only-password", 204);
    mvc.perform(
            post("/api/auth/password")
                .session(session)
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"currentPassword\":\"incorrect\",\"password\":\"new-test-password\",\"confirmation\":\"new-test-password\"}"))
        .andExpect(status().isConflict());
    assertFalse(session.isInvalid());
    mvc.perform(
            post("/api/auth/password")
                .session(session)
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"currentPassword\":\"test-only-password\",\"password\":\"new-test-password\",\"confirmation\":\"new-test-password\"}"))
        .andExpect(status().isOk());
    assertTrue(session.isInvalid());
    login("defensor", "test-only-password", 401);
    login("defensor", "new-test-password", 204);
  }

  @Test
  void publicActivationRequiresCsrfAndDoesNotAuthenticate() throws Exception {
    var invite = accounts.invite(pending(), "rh");
    String body =
        "{\"token\":\""
            + invite.token()
            + "\",\"password\":\"personal-test-password\",\"confirmation\":\"personal-test-password\"}";
    mvc.perform(post("/api/auth/activation").contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/auth/activation").with(csrf()).contentType("application/json").content(body))
        .andExpect(status().isOk());
    mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void defenderCannotIssueInvitesAndRhCannotReadDefenderAgenda() throws Exception {
    mvc.perform(
            post("/api/rh/defenders/" + pending() + "/invitation")
                .with(user("defensor").roles("DEFENSOR"))
                .with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/defender/appointments").with(user("rh").roles("RH")))
        .andExpect(status().isForbidden());
  }
}
