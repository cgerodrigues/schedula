package io.github.cgerodrigues.schedula.identity;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountController {
  private final AccountService accounts;

  public AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  public record Token(@NotBlank @Size(max = 100) String token) {
    @Override
    public String toString() {
      return "Token[redacted]";
    }
  }

  public record Activation(
      @NotBlank @Size(max = 100) String token,
      @NotBlank @Size(max = 72) String password,
      @NotBlank @Size(max = 72) String confirmation) {
    @Override
    public String toString() {
      return "Activation[redacted]";
    }
  }

  public record PasswordChange(
      @NotBlank @Size(max = 200) String currentPassword,
      @NotBlank @Size(max = 72) String password,
      @NotBlank @Size(max = 72) String confirmation) {
    @Override
    public String toString() {
      return "PasswordChange[redacted]";
    }
  }

  @PostMapping("/api/rh/defenders/{id}/invitation")
  public AccountService.Invitation invite(@PathVariable UUID id, Principal actor) {
    return accounts.invite(id, actor.getName());
  }

  @DeleteMapping("/api/rh/defenders/{id}/invitation")
  public void revoke(@PathVariable UUID id, Principal actor) {
    accounts.revoke(id, actor.getName());
  }

  @PostMapping("/api/auth/activation/check")
  public void check(@Valid @RequestBody Token token) {
    accounts.check(token.token());
  }

  @PostMapping("/api/auth/activation")
  public void activate(
      @Valid @RequestBody Activation body,
      HttpServletRequest request,
      HttpServletResponse response) {
    accounts.activate(body.token(), body.password(), body.confirmation());
    new SecurityContextLogoutHandler().logout(request, response, null);
  }

  @PostMapping("/api/auth/password")
  public void password(
      @Valid @RequestBody PasswordChange body,
      Authentication actor,
      HttpServletRequest request,
      HttpServletResponse response) {
    accounts.changePassword(
        actor.getName(), body.currentPassword(), body.password(), body.confirmation());
    new SecurityContextLogoutHandler().logout(request, response, actor);
  }
}
