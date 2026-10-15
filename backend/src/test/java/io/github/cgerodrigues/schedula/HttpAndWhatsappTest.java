package io.github.cgerodrigues.schedula;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cgerodrigues.schedula.notification.*;
import io.github.cgerodrigues.schedula.whatsapp.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

class HttpAndWhatsappTest extends IntegrationBase {
  @Autowired MockMvc mvc;
  @Autowired ConversationService conversations;
  @Autowired WebhookSignature signatures;
  @Autowired PasswordEncoder passwords;

  @Test
  void endpointsRequireAuthenticationAndCsrf() throws Exception {
    mvc.perform(get("/api/rh/units")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/rh/units")
                .with(user("rh").roles("RH"))
                .contentType("application/json")
                .content("{} "))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/rh/units").with(user("defensor").roles("DEFENSOR")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/rh/units")
                .with(user("rh").roles("RH"))
                .with(csrf())
                .contentType("application/json")
                .content("{} "))
        .andExpect(status().isBadRequest());
  }

  @Test
  void realLoginCreatesSessionAndLogoutClearsIt() throws Exception {
    var result =
        mvc.perform(
                post("/api/auth/login")
                    .with(csrf())
                    .param("username", "defensor")
                    .param("password", "test-only-password"))
            .andExpect(status().isNoContent())
            .andReturn();
    var session =
        (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false);
    mvc.perform(get("/api/auth/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("DEFENSOR"));
    mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
        .andExpect(status().isNoContent());
    assertTrue(session.isInvalid());
  }

  @Test
  @WithMockUser(username = "outro", roles = "DEFENSOR")
  void resourceAuthorizationDoesNotLeakCitizenDetails() throws Exception {
    var a = appointments.reserve(slot(9), "550000000001", "Maria", "Silva");
    mvc.perform(get("/api/defender/appointments"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
    mvc.perform(post("/api/defender/appointments/" + a.id() + "/cancel").with(csrf()))
        .andExpect(status().isConflict());
    assertEquals("CONFIRMADO", appointmentStatus(a.id()));
  }

  @Test
  void verificationAndSignature() throws Exception {
    mvc.perform(
            get("/webhooks/whatsapp")
                .param("hub.mode", "subscribe")
                .param("hub.verify_token", "test-only-verify-token")
                .param("hub.challenge", "42"))
        .andExpect(status().isOk())
        .andExpect(content().string("42"));
    mvc.perform(
            get("/webhooks/whatsapp")
                .param("hub.mode", "subscribe")
                .param("hub.verify_token", "wrong"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/webhooks/whatsapp").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    assertFalse(signatures.valid("{}".getBytes(StandardCharsets.UTF_8), "sha256=zz"));
  }

  private void message(String id, String text) throws Exception {
    String body =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .writeValueAsString(
                Map.of(
                    "object",
                    "whatsapp_business_account",
                    "entry",
                    List.of(
                        Map.of(
                            "changes",
                            List.of(
                                Map.of(
                                    "value",
                                    Map.of(
                                        "metadata",
                                        Map.of("phone_number_id", "123456"),
                                        "messages",
                                        List.of(
                                            Map.of(
                                                "id",
                                                id,
                                                "from",
                                                "550000000001",
                                                "type",
                                                "text",
                                                "text",
                                                Map.of("body", text))))))))));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec("test-only-app-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String signature =
        "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    mvc.perform(
            post("/webhooks/whatsapp")
                .contentType("application/json")
                .header("X-Hub-Signature-256", signature)
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void signedEndToEndFlowResumesAndDeduplicates() throws Exception {
    slot(9);
    message("1", "oi");
    message("2", "1");
    message("3", "1");
    message("4", "1");
    message("resume", "RETOMAR");
    message("5", "1");
    message("6", "Maria");
    message("7", "Silva");
    message("8", "SIM");
    message("8", "SIM");
    message("9", "SIM");
    assertEquals(1, appointments.list(defender).size());
    assertEquals(10, db.jdbc.queryForObject("select count(*) from whatsapp_inbox", Integer.class));
    assertEquals(
        10, db.jdbc.queryForObject("select count(*) from notification_outbox", Integer.class));
    assertTrue(
        db.exists(
            "select exists(select 1 from notification_outbox where body like 'Agendamento"
                + " confirmado! Protocolo:%')"));
    UUID protocol = (UUID) db.one("select protocol from appointment").get("protocol");
    message("cancel", "CANCELAR " + protocol);
    assertEquals("CANCELADO_CIDADAO", db.one("select status from appointment").get("status"));
  }

  @Test
  void unavailableSlotProducesRecoverableReply() throws Exception {
    UUID s = slot(9);
    message("1", "oi");
    message("2", "1");
    message("3", "1");
    message("4", "1");
    message("5", "1");
    message("6", "Maria");
    message("7", "Silva");
    appointments.reserve(s, "550000000002", "Ana", "Silva");
    message("8", "SIM");
    assertEquals(1, db.jdbc.queryForObject("select count(*) from appointment", Integer.class));
    assertTrue(
        db.exists(
            "select exists(select 1 from notification_outbox where body like 'Vaga já"
                + " reservada.%')"));
    message("9", "MENU");
  }

  @Test
  void noSlotsAndUrgencyGiveUnitContact() throws Exception {
    message("1", "oi");
    message("2", "1");
    message("3", "1");
    message("4", "1");
    message("5", "URGENTE");
    assertTrue(
        db.exists(
            "select exists(select 1 from notification_outbox where body like 'Não há vagas"
                + " disponíveis.%8533334444%')"));
    assertTrue(
        db.exists(
            "select exists(select 1 from notification_outbox where body like 'Para atendimento"
                + " urgente,%')"));
  }

  @Test
  void officialMetaPayloadUsesTemplateForProactiveMessages() {
    var meta = new MetaClient("", "123456", "v1.0", "schedula_notification", "pt_BR");
    assertEquals("template", meta.payload("550000000001", "Aviso", true).get("type"));
    assertEquals("text", meta.payload("550000000001", "Oi", false).get("type"));
  }

  @Test
  void duplicateMessagesAreSafeConcurrently() throws Exception {
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var a = executor.submit(() -> conversations.receive("same-id", "550000000001", "OI"));
      var b = executor.submit(() -> conversations.receive("same-id", "550000000001", "OI"));
      a.get();
      b.get();
    }
    assertEquals(1, db.jdbc.queryForObject("select count(*) from whatsapp_inbox", Integer.class));
    assertEquals(
        1, db.jdbc.queryForObject("select count(*) from notification_outbox", Integer.class));
  }

  @Test
  void invalidCancellationIsAnsweredWithoutRollbackLoop() throws Exception {
    message("invalid-cancel", "CANCELAR " + UUID.randomUUID());
    assertEquals(1, db.jdbc.queryForObject("select count(*) from whatsapp_inbox", Integer.class));
    assertEquals(
        1, db.jdbc.queryForObject("select count(*) from notification_outbox", Integer.class));
  }

  @Test
  void rhCreatesUnitOverValidatedHttpApi() throws Exception {
    String body =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .writeValueAsString(unitInput(municipality, "Unidade HTTP"));
    mvc.perform(
            post("/api/rh/units")
                .with(user("rh").roles("RH"))
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").exists());
    mvc.perform(get("/api/rh/units").with(user("rh").roles("RH")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }
}
