package io.github.cgerodrigues.schedula;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.cgerodrigues.schedula.institution.*;
import io.github.cgerodrigues.schedula.shared.DomainException;
import java.io.IOException;
import java.net.http.*;
import org.junit.jupiter.api.Test;

class LocationClientsTest {
  @SuppressWarnings("unchecked")
  HttpClient http(String body, int status) throws Exception {
    var http = mock(HttpClient.class);
    var response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenAnswer(
            call -> {
              HttpRequest request = call.getArgument(0);
              when(response.body())
                  .thenReturn(
                      request.uri().getHost().contains("ibge")
                          ? body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                          : body);
              return response;
            });

    return http;
  }

  @Test
  void parsesOfficialIbgeShapeAndUsesBoundedTimeout() throws Exception {
    var http =
        http(
            "[{\"id\":2304400,\"nome\":\"Fortaleza\",\"microrregiao\":{\"mesorregiao\":{\"UF\":{\"sigla\":\"CE\"}}}}]",
            200);
    assertEquals("2304400", new IbgeClient(http).municipalities("CE").getFirst().code());
    verify(http)
        .send(
            argThat(
                r ->
                    r.uri().toString().endsWith("/CE/municipios")
                        && r.timeout().orElseThrow().getSeconds() == 12),
            any(HttpResponse.BodyHandler.class));
  }

  @Test
  void rejectsIbgeWrongStateAndServerFailure() throws Exception {
    assertThrows(DomainException.class, () -> new IbgeClient(http("[]", 200)).municipalities("CE"));
    assertThrows(DomainException.class, () -> new IbgeClient(http("{}", 503)).municipalities("CE"));
    assertThrows(
        DomainException.class,
        () ->
            new IbgeClient(http("[{\"id\":2611606,\"nome\":\"Recife\"}]", 200))
                .municipalities("CE"));
  }

  @Test
  void postalSuccessAndUnknownZip() throws Exception {
    var http =
        http(
            "{\"logradouro\":\"Rua"
                + " Exemplo\",\"bairro\":\"Centro\",\"localidade\":\"Fortaleza\",\"uf\":\"CE\",\"ibge\":\"2304400\"}",
            200);
    var result = new PostalClient(http).lookup("60175000");
    assertEquals("Rua Exemplo", result.street());
    assertEquals("CE", result.stateCode());
    assertEquals("2304400", result.ibgeCode());
    assertThrows(
        DomainException.class,
        () -> new PostalClient(http("{\"erro\":true}", 200)).lookup("00000000"));
    assertThrows(
        DomainException.class,
        () -> new PostalClient(http("{\"erro\":\"true\"}", 200)).lookup("00000000"));
    assertThrows(DomainException.class, () -> new PostalClient(http).lookup("60175"));
    verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test
  void timeoutAndNetworkFailureAreActionable() throws Exception {
    for (Exception cause :
        new Exception[] {new HttpTimeoutException("timeout"), new IOException("offline")}) {
      var http = mock(HttpClient.class);
      when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(cause);
      assertTrue(
          assertThrows(DomainException.class, () -> new PostalClient(http).lookup("60175000"))
              .getMessage()
              .contains("manualmente"));
      assertThrows(DomainException.class, () -> new IbgeClient(http).municipalities("CE"));
    }
  }

  @Test
  void brazilPhonesNormalizeAndRejectBadInput() {
    assertEquals("8533334444", Brazil.phone("(85) 3333-4444"));
    assertEquals("85999999999", Brazil.phone("(85) 99999-9999"));
    for (String value :
        new String[] {
          "", "123", "(20) 3333-4444", "8533334444a", "853333444455", "85833334444", "8513334444"
        }) assertThrows(DomainException.class, () -> Brazil.phone(value), value);
  }

  @Test
  @SuppressWarnings("unchecked")
  void decompressesOfficialGzipResponse() throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) {
      gzip.write(
          "[{\"id\":2304400,\"nome\":\"Fortaleza\",\"regiao-imediata\":{\"regiao-intermediaria\":{\"UF\":{\"sigla\":\"CE\"}}}}]"
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    var http = mock(HttpClient.class);
    var response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(bytes.toByteArray());
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);
    assertEquals("Fortaleza", new IbgeClient(http).municipalities("CE").getFirst().name());
  }
}
