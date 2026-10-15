package io.github.cgerodrigues.schedula.identity;

import io.github.cgerodrigues.schedula.shared.Database;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class BootstrapAdmin {
  @Bean
  ApplicationRunner bootstrap(
      Database db,
      PasswordEncoder encoder,
      @Value("${schedula.admin.username:}") String username,
      @Value("${schedula.admin.password:}") String password) {
    return args -> {
      if (!username.isBlank() && !password.isBlank()) {
        if (db.exists("select exists(select 1 from staff_user where username=?)", username)) return;
        AccountService.validatePassword(password);
        db.jdbc.update(
            "insert into staff_user(id,username,password_hash,name,role) values"
                + " (?,?,?,'Administrador institucional','RH') on conflict(username) do nothing",
            UUID.randomUUID(),
            username,
            encoder.encode(password));
      }
    };
  }
}
