package io.github.cgerodrigues.schedula.identity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rh/defenders")
public class DefenderAdministrationController {
  private final DefenderAdministration defenders;

  public DefenderAdministrationController(DefenderAdministration defenders) {
    this.defenders = defenders;
  }

  public record Edit(@NotBlank @Size(max = 150) String name) {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
      throw new IllegalArgumentException("Somente o nome pode ser alterado pelo RH.");
    }
  }

  @GetMapping("/indicators")
  public Object indicators() { return defenders.indicators(); }

  @GetMapping("/search")
  public Object search(@RequestParam(defaultValue = "") String name,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return defenders.search(name, status, page, size);
  }

  @GetMapping("/{id}")
  public Object get(@PathVariable UUID id) { return defenders.get(id); }

  @PutMapping("/{id}")
  public void edit(@PathVariable UUID id, @Valid @RequestBody Edit body, Principal actor) {
    defenders.edit(id, body.name(), actor.getName());
  }
}
