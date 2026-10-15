package io.github.cgerodrigues.schedula.notification;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Component
public class MetaClient {
  private final RestClient client;
  private final String token, phoneId, version, template, language;

  @org.springframework.beans.factory.annotation.Autowired
  public MetaClient(
      @Value("${whatsapp.access-token:}") String token,
      @Value("${whatsapp.phone-number-id:}") String phoneId,
      @Value("${whatsapp.graph-version:}") String version,
      @Value("${whatsapp.template-name:}") String template,
      @Value("${whatsapp.template-language:pt_BR}") String language) {
    this(token, phoneId, version, template, language, productionBuilder());
  }

  MetaClient(
      String token,
      String phoneId,
      String version,
      String template,
      String language,
      RestClient.Builder builder) {
    this.token = token;
    this.phoneId = phoneId;
    this.version = version;
    this.template = template;
    this.language = language;
    client = builder.baseUrl("https://graph.facebook.com").build();
  }

  private static RestClient.Builder productionBuilder() {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder().requestFactory(factory);
  }

  public Map<String, Object> payload(String phone, String body, boolean proactive) {
    var result = new HashMap<String, Object>();
    result.put("messaging_product", "whatsapp");
    result.put("to", phone);
    if (proactive) {
      if (template.isBlank())
        throw new IntegrationFailure(IntegrationFailure.Code.CONFIGURATION, false);
      result.put("type", "template");
      result.put(
          "template",
          Map.of(
              "name",
              template,
              "language",
              Map.of("code", language),
              "components",
              List.of(
                  Map.of(
                      "type",
                      "body",
                      "parameters",
                      List.of(Map.of("type", "text", "text", body))))));
    } else {
      result.put("type", "text");
      result.put("text", Map.of("body", body));
    }
    return result;
  }

  public String send(String phone, String body, boolean proactive) {
    if (token.isBlank() || !phoneId.matches("[0-9]+") || !version.matches("v[0-9]+\\.[0-9]+"))
      throw new IntegrationFailure(IntegrationFailure.Code.CONFIGURATION, false);
    try {
      String response =
          client
              .post()
              .uri("/{version}/{phoneId}/messages", version, phoneId)
              .header("Authorization", "Bearer " + token)
              .body(payload(phone, body, proactive))
              .retrieve()
              .body(String.class);
      return responseId(response);
    } catch (org.springframework.web.client.RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == 401 || status == 403)
        throw new IntegrationFailure(IntegrationFailure.Code.HTTP_AUTH, false);
      if (status == 429)
        throw new IntegrationFailure(IntegrationFailure.Code.HTTP_RATE_LIMIT, true);
      if (status >= 500 || status == 408)
        throw new IntegrationFailure(IntegrationFailure.Code.HTTP_SERVER, true);
      throw new IntegrationFailure(IntegrationFailure.Code.HTTP_CLIENT, false);
    } catch (org.springframework.web.client.RestClientException e) {
      throw new IntegrationFailure(IntegrationFailure.Code.NETWORK, true);
    }
  }

  static String responseId(String response) {
    if (response == null || response.isBlank()) throw invalidResponse();
    try {
      var root = JsonMapper.builder().build().readTree(response);
      var messages = root.path("messages");
      if (!messages.isArray() || messages.isEmpty()) throw invalidResponse();
      var id = messages.get(0).path("id");
      if (!id.isString() || id.asText().isBlank()) throw invalidResponse();
      return id.asText();
    } catch (tools.jackson.core.JacksonException e) {
      throw invalidResponse();
    }
  }

  private static IntegrationFailure invalidResponse() {
    return new IntegrationFailure(IntegrationFailure.Code.INVALID_RESPONSE, true);
  }
}
