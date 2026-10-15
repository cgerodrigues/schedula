package io.github.cgerodrigues.schedula.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {
  static class MutableClock extends Clock {
    Instant now = Instant.EPOCH;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  @Test
  void windowExpiresAndMemoryLimitFailsClosed() {
    var clock = new MutableClock();
    var limiter = new LoginAttemptLimiter(clock, 2, 60, 1);
    assertEquals(0, limiter.retryAfter("one"));
    assertEquals(0, limiter.retryAfter("one"));
    assertEquals(60, limiter.retryAfter("one"));
    assertTrue(limiter.retryAfter("two") > 0);
    clock.now = clock.now.plusSeconds(60);
    assertEquals(0, limiter.retryAfter("two"));
  }

  @Test
  void concurrentRequestsShareSameBudget() throws Exception {
    var limiter = new LoginAttemptLimiter(Clock.systemUTC(), 3, 60, 10);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var results = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
      for (int i = 0; i < 20; i++) results.add(executor.submit(() -> limiter.retryAfter("one")));
      int allowed = 0;
      for (var r : results) if (r.get() == 0) allowed++;
      assertEquals(3, allowed);
    }
  }
}
