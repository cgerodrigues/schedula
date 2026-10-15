package io.github.cgerodrigues.schedula.institution;

import static io.github.cgerodrigues.schedula.shared.DomainException.require;

import io.github.cgerodrigues.schedula.shared.DomainException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PostalClient {
  private final HttpClient http;

  @org.springframework.beans.factory.annotation.Autowired
  public PostalClient() {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
  }

  public PostalClient(HttpClient http) {
    this.http = http;
  }

  public record Address(
      String postalCode,
      String street,
      String neighborhood,
      String city,
      String stateCode,
      String ibgeCode) {}

  public Address lookup(String cep) {
    require(cep != null && cep.matches("[0-9]{8}"), "CEP deve conter oito dígitos");
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create("https://viacep.com.br/ws/" + cep + "/json/"))
                  .timeout(Duration.ofSeconds(6))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      require(
          response.statusCode() == 200, "ViaCEP indisponível. Preencha o endereço manualmente.");
      var node = JsonMapper.builder().build().readTree(response.body());
      require(
          !node.path("erro").asBoolean(false),
          "CEP não encontrado. Confira ou preencha o endereço manualmente.");
      String uf = Brazil.state(node.path("uf").asText());
      String code = node.path("ibge").asText();
      require(code.matches("[0-9]{7}"), "ViaCEP retornou município inválido");
      return new Address(
          cep,
          node.path("logradouro").asText(),
          node.path("bairro").asText(),
          node.path("localidade").asText(),
          uf,
          code);
    } catch (DomainException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DomainException("Consulta de CEP interrompida. Preencha manualmente.");
    } catch (Exception e) {
      throw new DomainException(
          "ViaCEP indisponível ou demorou a responder. Preencha o endereço manualmente.");
    }
  }
}
