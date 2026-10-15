package io.github.cgerodrigues.schedula.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MetaClientTest {
  @Test
  void validResponseReturnsId() {
    assertEquals("wamid.test", MetaClient.responseId("{\"messages\":[{\"id\":\"wamid.test\"}]}"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "not json",
        "null",
        "{}",
        "{\"messages\":{}}",
        "{\"messages\":[]}",
        "{\"messages\":[{}]}",
        "{\"messages\":[null]}",
        "{\"messages\":[{\"id\":\"\"}]}",
        "{\"messages\":[{\"id\":\"  \"}]}",
        "{\"messages\":[{\"id\":42}]}",
        "{\"messages\":[{\"id\":null}]}"
      })
  void malformedResponsesAreControlled(String body) {
    var error = assertThrows(IntegrationFailure.class, () -> MetaClient.responseId(body));
    assertEquals(IntegrationFailure.Code.INVALID_RESPONSE, error.code());
    assertTrue(error.retryable());
    assertNull(error.getCause());
  }

  @ParameterizedTest
  @CsvSource({
    "401,false,HTTP_AUTH",
    "403,false,HTTP_AUTH",
    "429,true,HTTP_RATE_LIMIT",
    "500,true,HTTP_SERVER",
    "503,true,HTTP_SERVER",
    "400,false,HTTP_CLIENT"
  })
  void httpFailuresAreClassified(int status, boolean retryable, String code) {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    var client = new MetaClient("test-only-token", "123", "v1.0", "notice", "pt_BR", builder);
    server
        .expect(requestTo("https://graph.facebook.com/v1.0/123/messages"))
        .andRespond(
            withStatus(HttpStatusCode.valueOf(status)).body("sensitive response must not escape"));
    var error =
        assertThrows(IntegrationFailure.class, () -> client.send("550000000001", "Aviso", false));
    assertEquals(code, error.code().name());
    assertEquals(retryable, error.retryable());
    assertEquals(code, error.getMessage());
    assertNull(error.getCause());
    server.verify();
  }

  @Test
  void successfulHttpResponseIsValidated() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    var client = new MetaClient("test-only-token", "123", "v1.0", "notice", "pt_BR", builder);
    server
        .expect(requestTo("https://graph.facebook.com/v1.0/123/messages"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    assertThrows(IntegrationFailure.class, () -> client.send("550000000001", "Aviso", false));
    server.verify();
  }

  @Test
  void transportFailureIsRetryable() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    var client = new MetaClient("test-only-token", "123", "v1.0", "notice", "pt_BR", builder);
    server
        .expect(requestTo("https://graph.facebook.com/v1.0/123/messages"))
        .andRespond(withException(new java.io.IOException("network unavailable")));
    var error =
        assertThrows(IntegrationFailure.class, () -> client.send("550000000001", "Aviso", false));
    assertEquals(IntegrationFailure.Code.NETWORK, error.code());
    assertTrue(error.retryable());
  }
}
