package io.github.cgerodrigues.schedula.notification;

import io.github.cgerodrigues.schedula.shared.Database;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@EnableScheduling
public class OutboxWorker {
  private final Database db;
  private final MetaClient meta;
  private final TransactionTemplate tx;
  private final boolean enabled;

  public OutboxWorker(
      Database db,
      MetaClient meta,
      PlatformTransactionManager manager,
      @Value("${whatsapp.enabled:false}") boolean enabled) {
    this.db = db;
    this.meta = meta;
    tx = new TransactionTemplate(manager);
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 5000)
  public void deliver() {
    if (enabled) deliverOne();
  }

  public void deliverOne() {
    tx.executeWithoutResult(
        t -> {
          var rows =
              db.list(
                  "select * from notification_outbox o where status='PENDING' and"
                      + " next_attempt<=now() and not exists(select 1 from notification_outbox"
                      + " earlier where earlier.phone=o.phone and earlier.status='PENDING' and"
                      + " earlier.created_at<o.created_at) order by created_at limit 1 for update"
                      + " skip locked");
          if (rows.isEmpty()) return;
          var row = rows.getFirst();
          var id = (UUID) row.get("id");
          int attempts = ((Number) row.get("attempts")).intValue() + 1;
          final String messageId;
          try {
            boolean template =
                (boolean) row.get("template")
                    || io.github.cgerodrigues.schedula.appointment.AppointmentService.instant(
                            row, "created_at")
                        .isBefore(java.time.Instant.now().minus(java.time.Duration.ofHours(23)));
            messageId = meta.send((String) row.get("phone"), (String) row.get("body"), template);
            if (messageId == null || messageId.isBlank())
              throw new IntegrationFailure(IntegrationFailure.Code.INVALID_RESPONSE, true);
          } catch (RuntimeException failure) {
            // Catch only the external send. Database failures must still roll back.
            boolean retryable = !(failure instanceof IntegrationFailure known) || known.retryable();
            String code =
                failure instanceof IntegrationFailure known
                    ? known.code().name()
                    : failure instanceof org.springframework.web.client.RestClientException
                        ? "NETWORK"
                        : "UNEXPECTED_SEND";
            db.jdbc.update(
                "update notification_outbox set"
                    + " status=?,attempts=?,next_attempt=now()+make_interval(secs=>?) where id=?",
                !retryable || attempts >= 8 ? "FAILED" : "PENDING",
                attempts,
                Math.min(3600, 5 * (1 << attempts)),
                id);
            db.audit("outbox", "NOTIFICATION_FAILURE_" + code, id);
            org.slf4j.LoggerFactory.getLogger(OutboxWorker.class)
                .warn("Notification {} attempt {} failed: {}", id, attempts, code);
            return;
          }
          db.jdbc.update(
              "update notification_outbox set status='SENT',attempts=?,meta_message_id=? where"
                  + " id=?",
              attempts,
              messageId,
              id);
        });
  }
}
