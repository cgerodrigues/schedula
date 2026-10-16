package io.github.cgerodrigues.schedula.institution;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rh")
public class InstitutionController {
  private final InstitutionService service;
  private final UnitService units;
  private final MunicipalityCatalog catalog;
  private final PostalClient postal;

  public InstitutionController(
      InstitutionService s, UnitService units, MunicipalityCatalog catalog, PostalClient postal) {
    service = s;
    this.units = units;
    this.catalog = catalog;
    this.postal = postal;
  }

  @GetMapping("/municipalities")
  public Object municipalities() {
    return service.municipalities();
  }

  @PostMapping("/municipalities")
  public Object municipality(@Valid @RequestBody InstitutionService.Municipality r, Principal p) {
    return Map.of("id", service.municipality(r, p.getName()));
  }

  @GetMapping("/units")
  public Object units() {
    return service.units();
  }

  @GetMapping("/units/search")
  public Object searchUnits(
      @RequestParam(defaultValue = "") String name,
      @RequestParam(defaultValue = "") String municipality,
      @RequestParam(defaultValue = "") String area,
      @RequestParam(required = false) Boolean active,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return service.searchUnits(name, municipality, area, active, page, size);
  }

  @GetMapping("/units/{id}")
  public Object unit(@PathVariable UUID id) {
    return service.unit(id);
  }

  @PostMapping("/units")
  public Object unit(@Valid @RequestBody UnitService.Input r, Principal p) {
    return Map.of("id", units.save(null, r, p.getName()));
  }

  @PutMapping("/units/{id}")
  public Object editUnit(
      @PathVariable UUID id, @Valid @RequestBody UnitService.Input r, Principal p) {
    return Map.of("id", units.save(id, r, p.getName()));
  }

  @GetMapping("/states")
  public Object states() {
    return Brazil.STATES;
  }

  @GetMapping("/catalog/{uf}")
  public Object catalog(@PathVariable String uf) {
    return catalog.list(uf, false);
  }

  @PostMapping("/catalog/{uf}/sync")
  public Object sync(@PathVariable String uf) {
    return catalog.list(uf, true);
  }

  @GetMapping("/postal/{cep}")
  public Object postal(@PathVariable String cep) {
    return postal.lookup(cep);
  }

  @GetMapping("/defenders")
  public Object defenders() {
    return service.defenders();
  }

  @PostMapping("/defenders")
  public Object defender(@Valid @RequestBody InstitutionService.Defender r, Principal p) {
    return Map.of("id", service.defender(r, p.getName()));
  }

  @GetMapping("/affiliations")
  public Object affiliations() {
    return service.affiliations(null);
  }

  @PostMapping("/affiliations")
  public Object affiliation(@Valid @RequestBody InstitutionService.Affiliation r, Principal p) {
    return Map.of("id", service.affiliation(r, p.getName()));
  }

  @DeleteMapping("/affiliations/{id}")
  public void remove(@PathVariable UUID id, Principal p) {
    service.removeAffiliation(id, p.getName());
  }
}
