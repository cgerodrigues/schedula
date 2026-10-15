package io.github.cgerodrigues.schedula.notification;

import io.github.cgerodrigues.schedula.shared.Database;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
  private final Database db;

  public NotificationService(Database db) {
    this.db = db;
  }

  public void enqueue(String phone, String body, boolean template) {
    db.jdbc.update(
        "insert into notification_outbox(id,phone,body,template) values (?,?,?,?)",
        UUID.randomUUID(),
        phone,
        body,
        template);
  }
}
