package io.github.cgerodrigues.schedula.shared;

import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class TimeConfig {
  @Bean
  Clock clock(@Value("${schedula.timezone:America/Fortaleza}") String zone) {
    return Clock.system(ZoneId.of(zone));
  }
}
