package io.github.cgerodrigues.schedula.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookSignature {
  private final String secret;

  public WebhookSignature(@Value("${whatsapp.app-secret:}") String secret) {
    this.secret = secret;
  }

  public boolean valid(byte[] body, String signature) {
    if (secret.isBlank() || signature == null || !signature.startsWith("sha256=")) return false;
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return MessageDigest.isEqual(
          mac.doFinal(body), HexFormat.of().parseHex(signature.substring(7)));
    } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }
}
