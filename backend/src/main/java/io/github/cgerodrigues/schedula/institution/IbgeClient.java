package io.github.cgerodrigues.schedula.institution;

import static io.github.cgerodrigues.schedula.shared.DomainException.require;

import io.github.cgerodrigues.schedula.shared.DomainException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class IbgeClient {
  private final HttpClient http;

  @org.springframework.beans.factory.annotation.Autowired
  public IbgeClient() {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
  }

  public IbgeClient(HttpClient http) {
    this.http = http;
  }

  private static byte[] decode(byte[] body) throws java.io.IOException {
    // The official endpoint can return gzip even without Accept-Encoding.
    if (body.length >= 2 && body[0] == (byte) 0x1f && body[1] == (byte) 0x8b)
      try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
        return gzip.readAllBytes();
      }
    return body;
  }

  public record City(String code, String name, String stateCode) {}

  public List<City> municipalities(String uf) {
    Brazil.state(uf);
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "https://servicodados.ibge.gov.br/api/v1/localidades/estados/"
                              + uf
                              + "/municipios"))
                  .timeout(Duration.ofSeconds(12))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      require(response.statusCode() == 200, "IBGE indisponível");
      var root = JsonMapper.builder().build().readTree(decode(response.body()));
      require(root.isArray() && !root.isEmpty(), "IBGE retornou catálogo inválido");
      var result = new ArrayList<City>();
      for (var node : root) {
        String state =
            node.path("microrregiao").path("mesorregiao").path("UF").path("sigla").asText();
        if (state.isBlank())
          state =
              node.path("regiao-imediata")
                  .path("regiao-intermediaria")
                  .path("UF")
                  .path("sigla")
                  .asText();
        require(uf.equals(state), "IBGE retornou UF incompatível");
        result.add(new City(node.path("id").asText(), node.path("nome").asText(), state));
      }
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DomainException("Consulta ao IBGE interrompida. Tente novamente.");
    } catch (Exception e) {
      throw new DomainException("IBGE indisponível. Tente sincronizar novamente em instantes.");
    }
  }
}
