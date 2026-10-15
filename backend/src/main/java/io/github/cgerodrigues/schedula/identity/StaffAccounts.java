package io.github.cgerodrigues.schedula.identity;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffAccounts extends JpaRepository<StaffAccount, UUID> {
  Optional<StaffAccount> findByUsername(String username);
}
