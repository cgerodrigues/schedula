package io.github.cgerodrigues.schedula.scheduling;

import io.github.cgerodrigues.schedula.appointment.AppointmentService;
import io.github.cgerodrigues.schedula.institution.InstitutionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/defender")
public class DefenderController {
  private final InstitutionService institution;
  private final SchedulingService scheduling;
  private final AppointmentService appointments;

  public DefenderController(InstitutionService i, SchedulingService s, AppointmentService a) {
    institution = i;
    scheduling = s;
    appointments = a;
  }

  private UUID id(Principal p) {
    return institution.staff(p.getName());
  }

  @GetMapping("/affiliations")
  public Object affiliations(Principal p) {
    return institution.affiliations(id(p));
  }

  @GetMapping("/slots")
  public Object slots(Principal p) {
    return scheduling.slots(id(p));
  }

  @PostMapping("/slots")
  public Object publish(@Valid @RequestBody SchedulingService.Publish r, Principal p) {
    return Map.of("id", scheduling.publish(id(p), r));
  }

  @GetMapping("/blocks")
  public Object blocks(Principal p) {
    return scheduling.blocks(id(p));
  }

  @PostMapping("/blocks")
  public Object block(@Valid @RequestBody SchedulingService.Block r, Principal p) {
    return Map.of("id", scheduling.block(id(p), r));
  }

  @DeleteMapping("/series/{series}")
  public void withdraw(@PathVariable UUID series, Principal p) {
    scheduling.withdraw(id(p), series);
  }

  @GetMapping("/appointments")
  public Object appointments(Principal p) {
    return appointments.list(id(p));
  }

  @PostMapping("/appointments/{appointment}/cancel")
  public void cancel(@PathVariable UUID appointment, Principal p) {
    appointments.cancel(appointment, id(p));
  }

  public record Reschedule(@NotNull UUID slotId) {}

  @PostMapping("/appointments/{appointment}/reschedule")
  public Object reschedule(
      @PathVariable UUID appointment, @Valid @RequestBody Reschedule r, Principal p) {
    return appointments.reschedule(appointment, id(p), r.slotId());
  }

  public record Status(@NotBlank String status) {}

  @PostMapping("/appointments/{appointment}/status")
  public void status(@PathVariable UUID appointment, @Valid @RequestBody Status r, Principal p) {
    appointments.status(appointment, id(p), r.status());
  }
}
