package vigil.connectors;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.connectors.ConnectorRecords.StoredCredential;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.CredentialPurpose;
import vigil.connectors.api.Delivery;
import vigil.connectors.api.MessageLine;
import vigil.connectors.api.NotificationChannelConnector;
import vigil.shared.TenantId;
import vigil.shared.TransactionGuard;

/**
 * The {@code ms_teams} connector (PM-K-05, AR-K-05, AR-K-17, AR-K-18): the Adaptive Card payload
 * posted to the Workflows webhook address held by the delivery address's credential reference,
 * through the installation's {@link TeamsSender} (AR-K-19, AR-R-32).
 */
@Component
class TeamsConnector implements NotificationChannelConnector {

  private static final int HTTP_OK_MIN = 200;
  private static final int HTTP_OK_MAX = 299;

  private final ConnectorRecords records;
  private final FileSecrets secrets;
  private final ConnectorHttp http;
  private final ConnectorTimings timings;
  private final TeamsSender sender;

  TeamsConnector(
      ConnectorRecords records,
      FileSecrets secrets,
      ConnectorHttp http,
      ConnectorTimings timings,
      TeamsSender sender) {
    this.records = records;
    this.secrets = secrets;
    this.http = http;
    this.timings = timings;
    this.sender = sender;
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.MS_TEAMS;
  }

  /**
   * {@inheritDoc}
   *
   * <p>external call: not inside a transaction
   */
  @Override
  public Delivery send(
      TenantId tenantId,
      ConnectorInstanceView instance,
      String address,
      UUID credentialRefId,
      String senderName,
      List<MessageLine> lines) {
    TransactionGuard.assertNoTransaction();
    check(tenantId, instance, address, lines, kind());
    Optional<StoredCredential> credential =
        credentialRefId == null
            ? Optional.empty()
            : records
                .credential(tenantId, credentialRefId)
                .filter(c -> c.purpose() == CredentialPurpose.WEBHOOK_URL);
    if (credential.isEmpty()) {
      return new Delivery.Failed(AiConnector.NO_CREDENTIAL);
    }
    String name = credential.get().secretName();
    Optional<String> secret = secrets.readNamed(name);
    if (secret.isEmpty()) {
      return new Delivery.Failed("Secret " + name + " is not available.");
    }
    String webhook = secret.get();
    Optional<URI> uri =
        webhook.chars().anyMatch(Character::isWhitespace)
            ? Optional.empty()
            : HttpSourceConnector.address(webhook);
    if (uri.isEmpty()) {
      return new Delivery.Failed("Secret " + name + " is not a valid webhook address.");
    }
    List<String> hidden = new ArrayList<>();
    hidden.add(webhook);
    hidden.add(uri.get().getRawPath());
    hidden.add(uri.get().getRawQuery());
    hidden.add(uri.get().getPath());
    hidden.add(uri.get().getQuery());
    HttpRequest request;
    try {
      request =
          HttpRequest.newBuilder(uri.get())
              .timeout(timings.teams())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(payload(lines)))
              .build();
    } catch (IllegalArgumentException e) {
      return new Delivery.Failed("Secret " + name + " is not a valid webhook address.");
    }
    URI shown = shown(uri.get());
    return sender.post(
        () -> {
          HttpResponse<byte[]> response;
          try {
            response = http.send(request, timings.teams());
          } catch (Exception e) {
            return new Delivery.Failed(
                FailureReasons.of(e, shown, timings, timings.teams(), hidden));
          }
          int status = response.statusCode();
          return status >= HTTP_OK_MIN && status <= HTTP_OK_MAX
              ? new Delivery.Sent()
              : new Delivery.Failed("HTTP " + status);
        });
  }

  /** The Adaptive Card payload of AR-K-18 as UTF-8 JSON, keys in the order written. */
  static byte[] payload(List<MessageLine> lines) {
    ObjectNode message = JsonBodies.JSON.createObjectNode();
    message.put("type", "message");
    ObjectNode attachment = message.putArray("attachments").addObject();
    attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
    ObjectNode content = attachment.putObject("content");
    content.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
    content.put("type", "AdaptiveCard");
    content.put("version", "1.4");
    ArrayNode body = content.putArray("body");
    for (int i = 0; i < lines.size(); i++) {
      MessageLine line = lines.get(i);
      ObjectNode block = body.addObject();
      block.put("type", "TextBlock");
      block.put("text", text(line));
      block.put("wrap", true);
      if (i == 0) {
        block.put("weight", "Bolder");
      }
    }
    return JsonBodies.JSON.writeValueAsBytes(message);
  }

  /** The line's text, its link written as Markdown whose visible text is the address. */
  private static String text(MessageLine line) {
    if (line.link() == null) {
      return line.text();
    }
    String before = line.text().substring(0, line.text().length() - line.link().length());
    return before + "[" + line.link() + "](" + line.link() + ")";
  }

  /** The webhook's scheme, host and port only. */
  private static URI shown(URI uri) {
    try {
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null);
    } catch (URISyntaxException e) {
      throw new IllegalStateException("webhook host", e);
    }
  }

  /** The argument checks of a channel connector's {@code send} (P-22 spec Section 6.2). */
  static void check(
      TenantId tenantId,
      ConnectorInstanceView instance,
      String address,
      List<MessageLine> lines,
      ConnectorKind kind) {
    if (tenantId == null
        || instance == null
        || address == null
        || lines == null
        || lines.stream().anyMatch(line -> line == null)) {
      throw new IllegalArgumentException("a required argument is missing");
    }
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("lines: at least one");
    }
    if (instance.kind() != kind) {
      throw new IllegalArgumentException(
          "connector instance " + instance.id() + " is not " + kind.key());
    }
  }
}
