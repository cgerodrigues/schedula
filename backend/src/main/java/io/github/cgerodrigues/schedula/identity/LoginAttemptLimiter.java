package io.github.cgerodrigues.schedula.identity;

import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded, in-memory sliding window for the single-instance MVP. */
@Component
public class LoginAttemptLimiter {
  private final Clock clock;
  private final int maxAttempts, maxAddresses;
  private final long windowMillis;
  private final Map<String, Deque<Long>> attempts = new HashMap<>();

  public LoginAttemptLimiter(
      Clock clock,
      @Value("${schedula.login.max-attempts:10}") int maxAttempts,
      @Value("${schedula.login.window-seconds:300}") long seconds,
      @Value("${schedula.login.max-addresses:10000}") int maxAddresses) {
    if (maxAttempts < 1 || seconds < 1 || seconds > 86400 || maxAddresses < 1)
      throw new IllegalArgumentException("Limites de login inválidos");
    this.clock = clock;
    this.maxAttempts = maxAttempts;
    this.windowMillis = seconds * 1000;
    this.maxAddresses = maxAddresses;
  }

  /** Zero allows the attempt; a positive value is Retry-After seconds. */
  public synchronized long retryAfter(String remoteAddress) {
    long now = clock.millis(), cutoff = now - windowMillis;
    attempts
        .entrySet()
        .removeIf(
            entry -> {
              var values = entry.getValue();
              while (!values.isEmpty() && values.peekFirst() <= cutoff) values.removeFirst();
              return values.isEmpty();
            });
    var values = attempts.get(remoteAddress);
    if (values == null) {
      if (attempts.size() >= maxAddresses) return Math.max(1, windowMillis / 1000);
      values = new ArrayDeque<>();
      attempts.put(remoteAddress, values);
    }
    if (values.size() >= maxAttempts)
      return Math.max(1, (values.peekFirst() + windowMillis - now + 999) / 1000);
    values.addLast(now);
    return 0;
  }
}
