package io.github.cgerodrigues.schedula;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(properties = "schedula.login.max-attempts=3")
class LoginSecurityTest extends IntegrationBase {
  @Autowired MockMvc mvc;

  @Test
  void limitsByActualAddressDespiteUsernameAndHeaderChanges() throws Exception {
    for (int i = 0; i < 3; i++)
      mvc.perform(
              post("/api/auth/login")
                  .servletPath("/api/auth/login")
                  .with(csrf())
                  .param("username", "unknown" + i)
                  .param("password", "invalid-test-password"))
          .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/auth/login")
                .servletPath("/api/auth/login")
                .with(csrf())
                .header("X-Forwarded-For", "198.51.100.12")
                .param("username", "defensor")
                .param("password", "test-only-password"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
    mvc.perform(
            post("/api/auth/login")
                .servletPath("/api/auth/login")
                .with(csrf())
                .with(
                    r -> {
                      r.setRemoteAddr("198.51.100.13");
                      return r;
                    })
                .param("username", "defensor")
                .param("password", "test-only-password"))
        .andExpect(status().isNoContent());
  }
}
