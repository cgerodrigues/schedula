package io.github.cgerodrigues.schedula.identity;

import io.github.cgerodrigues.schedula.shared.Database;
import java.security.Principal;
import java.util.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final Database db;

  public AuthController(Database db) {
    this.db = db;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "header", token.getHeaderName());
  }

  @GetMapping("/me")
  public Map<String, Object> me(Principal p) {
    return db.one("select id,username,name,role from staff_user where username=?", p.getName());
  }
}
