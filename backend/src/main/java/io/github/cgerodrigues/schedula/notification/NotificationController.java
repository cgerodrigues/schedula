package io.github.cgerodrigues.schedula.notification;

import static io.github.cgerodrigues.schedula.shared.DomainException.require;

import io.github.cgerodrigues.schedula.shared.Database;
import java.security.Principal;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rh/notifications")
public class NotificationController {
  private final Database db;

  public NotificationController(Database db) {
    this.db = db;
  }

  @GetMapping
  public Object list() {
    return db.list(
        "select id,status,attempts,created_at,delivery_status from notification_outbox order by"
            + " created_at desc limit 100");
  }

  @PostMapping("/{id}/retry")
  @Transactional
  public void retry(@PathVariable UUID id, Principal actor) {
    require(
        db.jdbc.update(
                "update notification_outbox set status='PENDING',attempts=0,next_attempt=now()"
                    + " where id=? and status='FAILED'",
                id)
            == 1,
        "Somente envios com falha podem ser reenfileirados");
    db.audit(actor.getName(), "NOTIFICATION_REQUEUED", id);
  }
}
