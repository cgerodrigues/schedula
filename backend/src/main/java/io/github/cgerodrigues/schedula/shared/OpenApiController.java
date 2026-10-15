package io.github.cgerodrigues.schedula.shared;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

@RestController
public class OpenApiController {
  @GetMapping(value = "/api/openapi", produces = "application/json")
  public org.springframework.core.io.Resource document() {
    return new ClassPathResource("openapi.json");
  }
}
