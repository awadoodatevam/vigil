package vigil.connectors;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.ConnectorRecords.StoredCredential;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.TicketLine;
import vigil.connectors.api.TicketOutcome;
import vigil.connectors.api.TicketingConnector;
import vigil.shared.TenantId;
import vigil.shared.TransactionGuard;

/**
 * The {@code jira_cloud} connector (PM-K-04, AR-K-04, AR-K-14 to AR-K-16): the Jira Cloud REST API
 * v3 calls of P-21 spec Section 6.5, with the credential {@code {email, api_token}} (BP-A-29).
 */
@Component
class JiraConnector implements TicketingConnector {

  static final String NO_ISSUE_KEY = "The answer has no issue key.";
  static final String NO_STATUS = "The answer has no status.";
  static final String NO_ISSUES = "The answer has no issues.";
  private static final String ISSUE = "/rest/api/3/issue";
  private static final int HTTP_OK_MIN = 200;
  private static final int HTTP_OK_MAX = 299;

  private final ConnectorRecords records;
  private final FileSecrets secrets;
  private final ConnectorHttp http;
  private final ConnectorTimings timings;

  JiraConnector(
      ConnectorRecords records, FileSecrets secrets, ConnectorHttp http, ConnectorTimings timings) {
    this.records = records;
    this.secrets = secrets;
    this.http = http;
    this.timings = timings;
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.JIRA_CLOUD;
  }

  /**
   * {@inheritDoc}
   *
   * <p>external call: not inside a transaction
   */
  @Override
  public TicketOutcome create(
      TenantId tenantId,
      ConnectorInstanceView instance,
      String title,
      List<TicketLine> description,
      String label) {
    TransactionGuard.assertNoTransaction();
    required(tenantId, instance, title, description, label);
    if (description.stream().anyMatch(line -> line == null)) {
      throw new IllegalArgumentException("a required argument is missing");
    }
    kind(instance);
    Exchange exchange =
        exchange(
            instance,
            ISSUE,
            () -> {
              ObjectNode body = JsonBodies.JSON.createObjectNode();
              ObjectNode fields = body.putObject("fields");
              fields.putObject("project").put("key", setting(instance, "project_key"));
              fields.putObject("issuetype").put("name", setting(instance, "issue_type"));
              fields.put("summary", title);
              fields.set("description", Adf.document(description));
              fields.putArray("labels").add(label);
              return body;
            });
    if (exchange.failed() != null) {
      return exchange.failed();
    }
    String key = present(exchange.answer(), "key");
    return key == null
        ? new TicketOutcome.Failed(NO_ISSUE_KEY)
        : new TicketOutcome.Done(key, browse(exchange.base(), key), null);
  }

  /**
   * {@inheritDoc}
   *
   * <p>external call: not inside a transaction
   */
  @Override
  public TicketOutcome addComment(
      TenantId tenantId, ConnectorInstanceView instance, String key, List<TicketLine> lines) {
    TransactionGuard.assertNoTransaction();
    required(tenantId, instance, key, lines);
    if (lines.stream().anyMatch(line -> line == null)) {
      throw new IllegalArgumentException("a required argument is missing");
    }
    kind(instance);
    Exchange exchange =
        exchange(
            instance,
            ISSUE + "/" + key + "/comment",
            () -> {
              ObjectNode body = JsonBodies.JSON.createObjectNode();
              body.set("body", Adf.document(lines));
              return body;
            });
    return exchange.failed() != null ? exchange.failed() : new TicketOutcome.Done(key, null, null);
  }

  /**
   * {@inheritDoc}
   *
   * <p>external call: not inside a transaction
   */
  @Override
  public TicketOutcome transition(
      TenantId tenantId, ConnectorInstanceView instance, String key, String statusName) {
    TransactionGuard.assertNoTransaction();
    required(tenantId, instance, key, statusName);
    kind(instance);
    String path = ISSUE + "/" + key + "/transitions";
    Exchange list = exchange(instance, path, null);
    if (list.failed() != null) {
      return list.failed();
    }
    String id = transitionId(list.answer(), statusName);
    if (id == null) {
      return new TicketOutcome.Failed("No transition to status '" + statusName + "'.");
    }
    Exchange move =
        exchange(
            instance,
            path,
            () -> {
              ObjectNode body = JsonBodies.JSON.createObjectNode();
              body.putObject("transition").put("id", id);
              return body;
            });
    return move.failed() != null ? move.failed() : new TicketOutcome.Done(key, null, statusName);
  }

  /**
   * {@inheritDoc}
   *
   * <p>external call: not inside a transaction
   */
  @Override
  public TicketOutcome status(TenantId tenantId, ConnectorInstanceView instance, String key) {
    TransactionGuard.assertNoTransaction();
    required(tenantId, instance, key);
    kind(instance);
    Exchange exchange = exchange(instance, ISSUE + "/" + key + "?fields=status", null);
    if (exchange.failed() != null) {
      return exchange.failed();
    }
    String status = statusName(exchange.answer());
    return status == null
        ? new TicketOutcome.Failed(NO_STATUS)
        : new TicketOutcome.Done(key, null, status);
  }

  /**
   * {@inheritDoc}
   *
   * <p>external call: not inside a transaction
   */
  @Override
  public TicketOutcome findByLabel(
      TenantId tenantId, ConnectorInstanceView instance, String label) {
    TransactionGuard.assertNoTransaction();
    required(tenantId, instance, label);
    kind(instance);
    String jql = "project=" + setting(instance, "project_key") + " AND labels=\"" + label + "\"";
    String query =
        URLEncoder.encode(jql, StandardCharsets.UTF_8).replace("+", "%20") + "&fields=status";
    Exchange exchange = exchange(instance, "/rest/api/3/search/jql?jql=" + query, null);
    if (exchange.failed() != null) {
      return exchange.failed();
    }
    JsonNode issues = exchange.answer() == null ? null : exchange.answer().get("issues");
    if (issues == null || !issues.isArray()) {
      return new TicketOutcome.Failed(NO_ISSUES);
    }
    if (issues.isEmpty()) {
      return new TicketOutcome.Done(null, null, null);
    }
    JsonNode first = issues.get(0);
    String key = first.isObject() ? present(first, "key") : null;
    if (key == null) {
      return new TicketOutcome.Failed(NO_ISSUES);
    }
    return new TicketOutcome.Done(key, browse(exchange.base(), key), statusName(first));
  }

  /** The string setting {@code key} of the instance. */
  private static String setting(ConnectorInstanceView instance, String key) {
    return HttpSourceConnector.setting(instance, key);
  }

  /** A 2xx answer (as a JSON object, or {@code null} when it is none) or the failure. */
  private record Exchange(String base, JsonNode answer, TicketOutcome.Failed failed) {}

  /** The request body of a {@code POST}; a {@code null} supplier means a {@code GET}. */
  private interface Body {
    ObjectNode get();
  }

  private Exchange exchange(ConnectorInstanceView instance, String path, Body body) {
    String base = instance.address().replaceFirst("/+$", "");
    Optional<URI> uri = HttpSourceConnector.address(base + path);
    if (uri.isEmpty()) {
      return failed(HttpSourceConnector.INVALID_ADDRESS);
    }
    UUID credentialRefId = instance.credentialRefId();
    Optional<StoredCredential> credential =
        credentialRefId == null
            ? Optional.empty()
            : records.credential(instance.tenantId(), credentialRefId);
    if (credential.isEmpty()) {
      return failed(AiConnector.NO_CREDENTIAL);
    }
    String name = credential.get().secretName();
    Optional<String> secret = secrets.readNamed(name);
    if (secret.isEmpty()) {
      return failed("Secret " + name + " is not available.");
    }
    JsonNode value = JsonBodies.object(secret.get()).orElse(null);
    String email = value == null ? null : JsonBodies.present(value, "email");
    String token = value == null ? null : JsonBodies.present(value, "api_token");
    if (email == null || token == null) {
      return failed("Secret " + name + " is not valid.");
    }
    String basic =
        Base64.getEncoder().encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8));
    HttpRequest request;
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(uri.get())
              .timeout(timings.jira())
              .header("Authorization", "Basic " + basic)
              .header("Accept", "application/json");
      if (body == null) {
        builder.GET();
      } else {
        builder
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofByteArray(
                    JsonBodies.JSON.writeValueAsBytes(body.get())));
      }
      request = builder.build();
    } catch (IllegalArgumentException e) {
      return failed(HttpSourceConnector.INVALID_ADDRESS);
    }
    HttpResponse<byte[]> response;
    try {
      response = http.send(request, timings.jira());
    } catch (Exception e) {
      return failed(
          FailureReasons.of(e, uri.get(), timings, timings.jira(), List.of(token, email, basic)));
    }
    int status = response.statusCode();
    if (status < HTTP_OK_MIN || status > HTTP_OK_MAX) {
      return failed("HTTP " + status);
    }
    byte[] answer = response.body() == null ? new byte[0] : response.body();
    return new Exchange(
        base, JsonBodies.decode(answer).flatMap(JsonBodies::object).orElse(null), null);
  }

  private static Exchange failed(String reason) {
    return new Exchange(null, null, new TicketOutcome.Failed(reason));
  }

  /** The id of the first transition to {@code statusName}, or {@code null}. */
  private static String transitionId(JsonNode answer, String statusName) {
    JsonNode transitions = answer == null ? null : answer.get("transitions");
    if (transitions == null || !transitions.isArray()) {
      return null;
    }
    for (JsonNode transition : transitions) {
      JsonNode to = transition.isObject() ? transition.get("to") : null;
      if (to != null && to.isObject() && statusName.equals(JsonBodies.string(to, "name"))) {
        JsonNode id = transition.get("id");
        return id != null && id.isString() ? id.stringValue() : null;
      }
    }
    return null;
  }

  /** {@code fields.status.name} of an issue when it is a string, else {@code null}. */
  private static String statusName(JsonNode issue) {
    JsonNode fields = issue == null ? null : issue.get("fields");
    JsonNode status = fields == null || !fields.isObject() ? null : fields.get("status");
    return status == null || !status.isObject() ? null : JsonBodies.string(status, "name");
  }

  private static String present(JsonNode node, String field) {
    return node == null ? null : JsonBodies.present(node, field);
  }

  private static String browse(String base, String key) {
    return base + "/browse/" + key;
  }

  private void kind(ConnectorInstanceView instance) {
    if (instance.kind() != kind()) {
      throw new IllegalArgumentException(
          "connector instance " + instance.id() + " is not jira_cloud");
    }
  }

  private static void required(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
