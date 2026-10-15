package io.github.cgerodrigues.schedula.whatsapp;

import io.github.cgerodrigues.schedula.shared.Database;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/webhooks/whatsapp")
public class WebhookController {
  private final WebhookSignature signature;
  private final ConversationService conversations;
  private final Database db;
  private final String verifyToken;
  private final String phoneId;
  private final JsonMapper json = JsonMapper.builder().build();

  public WebhookController(
      WebhookSignature s,
      ConversationService c,
      Database d,
      @Value("${whatsapp.verify-token:}") String token,
      @Value("${whatsapp.phone-number-id:}") String phoneId) {
    signature = s;
    conversations = c;
    db = d;
    verifyToken = token;
    this.phoneId = phoneId;
  }

  @GetMapping
  public ResponseEntity<String> verify(
      @RequestParam(name = "hub.mode", defaultValue = "") String mode,
      @RequestParam(name = "hub.verify_token", defaultValue = "") String token,
      @RequestParam(name = "hub.challenge", defaultValue = "") String challenge) {
    return !verifyToken.isBlank()
            && mode.equals("subscribe")
            && java.security.MessageDigest.isEqual(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                verifyToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        ? ResponseEntity.ok(challenge)
        : ResponseEntity.status(403).build();
  }

  @PostMapping
  public ResponseEntity<Void> receive(
      @RequestBody byte[] body,
      @RequestHeader(name = "X-Hub-Signature-256", required = false) String header) {
    if (body.length > 262144) return ResponseEntity.status(413).build();
    if (!signature.valid(body, header)) return ResponseEntity.status(401).build();
    var root = json.readTree(body);
    if (!root.path("object").asText().equals("whatsapp_business_account"))
      return ResponseEntity.badRequest().build();
    for (var entry : root.path("entry"))
      for (var change : entry.path("changes")) {
        var value = change.path("value");
        if (!phoneId.equals(value.path("metadata").path("phone_number_id").asText())
            || phoneId.isBlank()) continue;
        for (var message : value.path("messages")) {
          String text =
              message.path("type").asText().equals("text")
                  ? message.path("text").path("body").asText()
                  : "RETOMAR";
          conversations.receive(message.path("id").asText(), message.path("from").asText(), text);
        }
        for (var status : value.path("statuses"))
          db.jdbc.update(
              "update notification_outbox set delivery_status=? where meta_message_id=?",
              status.path("status").asText(),
              status.path("id").asText());
      }
    return ResponseEntity.ok().build();
  }
}
