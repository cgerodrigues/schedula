package io.github.cgerodrigues.schedula.identity;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwords() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(StaffAccounts accounts) {
    return username -> {
      var account =
          accounts
              .findByUsername(username)
              .orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas"));
      return User.withUsername(account.username())
          .password(account.passwordHash())
          .roles(account.role())
          .disabled(!account.active())
          .build();
    };
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, LoginAttemptLimiter limiter) throws Exception {
    return http.addFilterBefore(
            new LoginRateLimitFilter(limiter),
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/auth/csrf",
                        "/api/auth/login",
                        "/api/auth/activation",
                        "/api/auth/activation/check",
                        "/webhooks/whatsapp",
                        "/actuator/health")
                    .permitAll()
                    .requestMatchers("/api/rh/**")
                    .hasRole("RH")
                    .requestMatchers("/api/defender/**")
                    .hasRole("DEFENSOR")
                    .anyRequest()
                    .authenticated())
        .csrf(c -> c.ignoringRequestMatchers("/webhooks/whatsapp"))
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/auth/login")
                    .successHandler((q, r, a) -> r.setStatus(204))
                    .failureHandler((q, r, e) -> r.setStatus(401)))
        .logout(
            l ->
                l.logoutUrl("/api/auth/logout").logoutSuccessHandler((q, r, a) -> r.setStatus(204)))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint((q, r, x) -> r.sendError(401))
                    .accessDeniedHandler((q, r, x) -> r.sendError(403)))
        .build();
  }
}
