package io.github.cgerodrigues.schedula.identity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "staff_user")
public class StaffAccount {
  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 120)
  private String username;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Column(nullable = false, length = 150)
  private String name;

  @Column(nullable = false, length = 20)
  private String role;

  @Column(nullable = false, length = 30)
  private String status;

  public boolean active() {
    return "ACTIVE".equals(status);
  }

  protected StaffAccount() {}

  public String username() {
    return username;
  }

  public String passwordHash() {
    return passwordHash;
  }

  public String role() {
    return role;
  }
}
