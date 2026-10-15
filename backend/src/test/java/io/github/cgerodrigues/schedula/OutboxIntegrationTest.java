package io.github.cgerodrigues.schedula;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.cgerodrigues.schedula.notification.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

class OutboxIntegrationTest extends IntegrationBase {
  @Autowired NotificationService notifications;
  @Autowired PlatformTransactionManager manager;

  @Test
  void retriesWithoutLosingNotificationAndPersistsMetaId() {
    var meta = mock(MetaClient.class);
    when(meta.send(anyString(), anyString(), anyBoolean()))
        .thenThrow(new org.springframework.web.client.RestClientException("offline"))
        .thenReturn("wamid.test");
    var worker = new OutboxWorker(db, meta, manager, false);
    notifications.enqueue("550000000001", "Aviso", true);
    worker.deliverOne();
    var pending = db.one("select * from notification_outbox");
    assertEquals("PENDING", pending.get("status"));
    assertEquals(1, pending.get("attempts"));
    db.jdbc.update("update notification_outbox set next_attempt=now()");
    worker.deliverOne();
    var sent = db.one("select * from notification_outbox");
    assertEquals("SENT", sent.get("status"));
    assertEquals("wamid.test", sent.get("meta_message_id"));
  }

  @Test
  void boundedRetriesAndDisabledDelivery() {
    var meta = mock(MetaClient.class);
    when(meta.send(anyString(), anyString(), anyBoolean()))
        .thenThrow(new IllegalStateException("missing config"));
    var worker = new OutboxWorker(db, meta, manager, false);
    notifications.enqueue("550000000001", "Aviso", true);
    worker.deliver();
    verifyNoInteractions(meta);
    for (int i = 0; i < 8; i++) {
      db.jdbc.update("update notification_outbox set next_attempt=now()");
      worker.deliverOne();
    }
    assertEquals("FAILED", db.one("select status from notification_outbox").get("status"));
  }

  @Test
  void invalidResponseAndUnexpectedSendCountAttemptsWithBackoff() {
    var meta = mock(MetaClient.class);
    when(meta.send(anyString(), anyString(), anyBoolean()))
        .thenThrow(new IntegrationFailure(IntegrationFailure.Code.INVALID_RESPONSE, true))
        .thenThrow(new NullPointerException("must not persist this message"));
    var worker = new OutboxWorker(db, meta, manager, false);
    notifications.enqueue("550000000001", "Aviso", false);
    worker.deliverOne();
    var row =
        db.one(
            "select attempts,status,extract(epoch from (next_attempt-now())) delay from"
                + " notification_outbox");
    assertEquals(1, row.get("attempts"));
    assertTrue(((Number) row.get("delay")).doubleValue() > 8);
    worker.deliverOne();
    verify(meta, times(1)).send(anyString(), anyString(), anyBoolean());
    db.jdbc.update("update notification_outbox set next_attempt=now()");
    worker.deliverOne();
    assertEquals(2, db.one("select attempts from notification_outbox").get("attempts"));
    assertTrue(
        db.exists(
            "select exists(select 1 from audit_event where"
                + " action='NOTIFICATION_FAILURE_UNEXPECTED_SEND')"));
  }

  @Test
  void permanentFailureStopsAndRhCanRetry() {
    var meta = mock(MetaClient.class);
    when(meta.send(anyString(), anyString(), anyBoolean()))
        .thenThrow(new IntegrationFailure(IntegrationFailure.Code.HTTP_AUTH, false))
        .thenReturn("wamid.fixed");
    notifications.enqueue("550000000001", "Aviso", true);
    var worker = new OutboxWorker(db, meta, manager, false);
    worker.deliverOne();
    var row = db.one("select * from notification_outbox");
    assertEquals("FAILED", row.get("status"));
    assertEquals(1, row.get("attempts"));
    new NotificationController(db).retry((java.util.UUID) row.get("id"), () -> "rh");
    worker.deliverOne();
    assertEquals("SENT", db.one("select status from notification_outbox").get("status"));
  }

  @Test
  void notificationFailureDoesNotUndoConfirmedReservation() {
    var booking = appointments.reserve(slot(9), "550000000001", "Maria", "Silva");
    notifications.enqueue("550000000001", "Confirmação", false);
    var meta = mock(MetaClient.class);
    when(meta.send(anyString(), anyString(), anyBoolean()))
        .thenThrow(new IntegrationFailure(IntegrationFailure.Code.NETWORK, true));
    new OutboxWorker(db, meta, manager, false).deliverOne();
    assertEquals("CONFIRMADO", appointmentStatus(booking.id()));
  }

  @Test
  void twoWorkersNeverSendSameRowSimultaneously() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var meta = mock(MetaClient.class);
    when(meta.send(anyString(), anyString(), anyBoolean()))
        .thenAnswer(
            call -> {
              entered.countDown();
              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException();
              return "wamid.single";
            });
    notifications.enqueue("550000000001", "Aviso", false);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> new OutboxWorker(db, meta, manager, false).deliverOne());
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      try {
        var second = executor.submit(() -> new OutboxWorker(db, meta, manager, false).deliverOne());
        second.get(5, java.util.concurrent.TimeUnit.SECONDS);
        verify(meta, times(1)).send(anyString(), anyString(), anyBoolean());
      } finally {
        release.countDown();
      }
      first.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertEquals("SENT", db.one("select status from notification_outbox").get("status"));
  }
}
