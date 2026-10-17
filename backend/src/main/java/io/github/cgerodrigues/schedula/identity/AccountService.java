package io.github.cgerodrigues.schedula.identity;

import static io.github.cgerodrigues.schedula.shared.DomainException.require;

import io.github.cgerodrigues.schedula.shared.Database;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  private final Database db;
  private final PasswordEncoder passwords;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public AccountService(Database db, PasswordEncoder passwords, Clock clock) {
    this.db = db;
    this.passwords = passwords;
    this.clock = clock;
  }

  public record Invitation(String token, OffsetDateTime expiresAt) {
    @Override
    public String toString() {
      return "Invitation[redacted]";
    }
  }

  public static void validatePassword(String password) {
    require(
        password != null
            && password.codePointCount(0, password.length()) >= 12
            && password.getBytes(StandardCharsets.UTF_8).length <= 72,
        "A senha deve ter ao menos 12 caracteres e no máximo 72 bytes UTF-8.");
  }

  private static String hash(String token) {
    require(
        token != null && token.matches("[A-Za-z0-9_-]{43}"), "Convite inválido ou indisponível.");
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponível");
    }
  }

  private Map<String, Object> account(UUID id) {
    return db.one("select * from staff_user where id=? for update", id);
  }

  @Transactional
  public Invitation invite(UUID id, String actor) {
    var account = account(id);
    require(
        account.get("role").equals("DEFENSOR")
            && account.get("status").equals("PENDING_ACTIVATION"),
        "Somente defensores pendentes podem receber convite.");
    revokeOpen(id);
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    var now = OffsetDateTime.now(clock);
    var expiry = now.plusHours(24);
    db.jdbc.update(
        "insert into account_invitation(id,account_id,token_hash,created_at,expires_at) values"
            + " (?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        hash(token),
        now,
        expiry);
    db.audit(actor, "INVITATION_ISSUED", id);
    return new Invitation(token, expiry);
  }

  private void revokeOpen(UUID id) {
    db.jdbc.update(
        "update account_invitation set revoked_at=? where account_id=? and used_at is null and"
            + " revoked_at is null",
        OffsetDateTime.now(clock),
        id);
  }

  @Transactional
  public void revoke(UUID id, String actor) {
    var account = account(id);
    require(account.get("role").equals("DEFENSOR") && account.get("status").equals("PENDING_ACTIVATION"),
        "Somente defensores pendentes podem ter convites revogados.");
    revokeOpen(id);
    db.audit(actor, "INVITATION_REVOKED", id);
  }

  private Map<String, Object> invitation(String token) {
    var rows = db.list("select * from account_invitation where token_hash=?", hash(token));
    require(!rows.isEmpty(), "Convite inválido ou indisponível.");
    return rows.getFirst();
  }

  private void valid(Map<String, Object> invite) {
    require(
        invite.get("used_at") == null && invite.get("revoked_at") == null,
        "Convite já utilizado ou revogado. Solicite outro ao RH.");
    require(
        io.github.cgerodrigues.schedula.appointment.AppointmentService.instant(invite, "expires_at")
            .isAfter(clock.instant()),
        "Convite expirado. Solicite outro ao RH.");
  }

  public record Identity(String name, String username) {}

  @Transactional
  public Identity check(String token) {
    var first = invitation(token);
    var account = account((UUID) first.get("account_id"));
    valid(invitation(token));
    require(account.get("status").equals("PENDING_ACTIVATION")
        && account.get("role").equals("DEFENSOR"), "Convite indisponível.");
    return new Identity((String) account.get("name"), (String) account.get("username"));
  }

  @Transactional
  public void activate(String token, String password, String confirmation) {
    validatePassword(password);
    require(password.equals(confirmation), "As senhas não coincidem.");
    var first = invitation(token);
    var id = (UUID) first.get("account_id");
    var account = account(id);
    // Issuance, revocation and consumption always lock the account first.
    var invite = invitation(token);
    valid(invite);
    require(account.get("status").equals("PENDING_ACTIVATION"), "Conta já ativada.");
    db.jdbc.update(
        "update staff_user set password_hash=?,status='ACTIVE' where id=?",
        passwords.encode(password),
        id);
    db.jdbc.update(
        "update account_invitation set used_at=? where id=?",
        OffsetDateTime.now(clock),
        invite.get("id"));
    db.audit(id.toString(), "ACCOUNT_ACTIVATED", id);
  }

  @Transactional
  public void changePassword(
      String username, String current, String password, String confirmation) {
    validatePassword(password);
    require(password.equals(confirmation), "As senhas não coincidem.");
    var account = db.one("select * from staff_user where username=? for update", username);
    require(
        current != null && passwords.matches(current, (String) account.get("password_hash")),
        "Senha atual incorreta.");
    db.jdbc.update(
        "update staff_user set password_hash=? where id=?",
        passwords.encode(password),
        account.get("id"));
    db.audit(username, "PASSWORD_CHANGED", (UUID) account.get("id"));
  }
}
