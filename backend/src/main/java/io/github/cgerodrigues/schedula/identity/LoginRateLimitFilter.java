package io.github.cgerodrigues.schedula.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class LoginRateLimitFilter extends OncePerRequestFilter {
  private final LoginAttemptLimiter limiter;

  public LoginRateLimitFilter(LoginAttemptLimiter limiter) {
    this.limiter = limiter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if ("POST".equals(request.getMethod()) && "/api/auth/login".equals(request.getServletPath())) {
      // Never trust user-provided usernames or forwarded headers as the throttle key.
      long retry = limiter.retryAfter(request.getRemoteAddr());
      if (retry > 0) {
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retry));
        response.setContentType("application/problem+json");
        response
            .getWriter()
            .write(
                "{\"status\":429,\"detail\":\"Muitas tentativas de login. Aguarde e tente"
                    + " novamente.\"}");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
