package vigil.connectors;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.connectors.ConnectorRecords.StoredCredential;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.Delivery;
import vigil.connectors.api.MessageLine;
import vigil.connectors.api.NotificationChannelConnector;
import vigil.shared.TenantId;
import vigil.shared.TransactionGuard;

/**
 * The {@code email} connector (PM-K-06, AR-K-06, AR-K-20, AR-T-14): one plain text email per
 * address over SMTP with Jakarta Mail, with the instance's SMTP credential.
 */
@Component
class EmailConnector implements NotificationChannelConnector {

  static final String INVALID_ADDRESS = "The address is not a valid smtp address.";
  static final String AUTHENTICATION = "SMTP authentication failed.";
  private static final String CRLF = "\r\n";
  private static final int PORT_MAX = 65535;
  private static final int REFUSAL_MAX = 400;

  private final ConnectorRecords records;
  private final FileSecrets secrets;
  private final ConnectorTimings timings;
  private final Clock clock;

  EmailConnector(
      ConnectorRecords records, FileSecrets secrets, ConnectorTimings timings, Clock clock) {
    this.records = records;
    this.secrets = secrets;
    this.timings = timings;
    this.clock = clock;
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.EMAIL;
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
    TeamsConnector.check(tenantId, instance, address, lines, kind());
    Optional<URI> server = server(instance.address());
    if (server.isEmpty()) {
      return new Delivery.Failed(INVALID_ADDRESS);
    }
    String host = server.get().getHost();
    int port = server.get().getPort();
    String from = HttpSourceConnector.setting(instance, "from_address");
    boolean starttls = starttls(instance);
    UUID reference = instance.credentialRefId();
    Optional<StoredCredential> credential =
        reference == null ? Optional.empty() : records.credential(tenantId, reference);
    if (credential.isEmpty()) {
      return new Delivery.Failed(AiConnector.NO_CREDENTIAL);
    }
    String name = credential.get().secretName();
    Optional<String> secret = secrets.readNamed(name);
    if (secret.isEmpty()) {
      return new Delivery.Failed("Secret " + name + " is not available.");
    }
    JsonNode value = JsonBodies.object(secret.get()).orElse(null);
    String username = value == null ? null : JsonBodies.present(value, "username");
    String password = value == null ? null : JsonBodies.present(value, "password");
    if (username == null || password == null) {
      return new Delivery.Failed("Secret " + name + " is not valid.");
    }
    Properties properties = new Properties();
    properties.put("mail.smtp.host", host);
    properties.put("mail.smtp.port", String.valueOf(port));
    properties.put("mail.smtp.auth", "true");
    properties.put("mail.smtp.starttls.enable", String.valueOf(starttls));
    properties.put("mail.smtp.starttls.required", String.valueOf(starttls));
    properties.put("mail.smtp.connectiontimeout", String.valueOf(timings.connect().toMillis()));
    properties.put("mail.smtp.timeout", String.valueOf(timings.smtp().toMillis()));
    properties.put("mail.smtp.writetimeout", String.valueOf(timings.smtp().toMillis()));
    Session session = Session.getInstance(properties);
    try {
      MimeMessage message = new MimeMessage(session);
      message.setFrom(new InternetAddress(from, senderName, "UTF-8"));
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(address, true));
      message.setSubject(lines.getFirst().text(), "UTF-8");
      message.setSentDate(Date.from(clock.instant()));
      message.setText(body(lines), "UTF-8", "plain");
      message.setHeader("Content-Transfer-Encoding", "8bit");
      Transport.send(message, username, password);
      return new Delivery.Sent();
    } catch (MessagingException | UnsupportedEncodingException e) {
      return new Delivery.Failed(reason(e, host, port, List.of(username, password)));
    }
  }

  /** The lines' texts joined by CRLF and ended by CRLF. */
  static String body(List<MessageLine> lines) {
    return lines.stream().map(MessageLine::text).collect(Collectors.joining(CRLF)) + CRLF;
  }

  /** The reason of a refusal or failure (P-22 spec Section 6.2, item 4). */
  private String reason(Exception failure, String host, int port, List<String> hidden) {
    List<Throwable> chain = new ArrayList<>();
    for (Throwable t = failure; t != null && !chain.contains(t); t = t.getCause()) {
      chain.add(t);
      if (t instanceof MessagingException messaging
          && messaging.getNextException() != null
          && messaging.getNextException() != t.getCause()) {
        chain.add(messaging.getNextException());
      }
    }
    if (any(chain, AuthenticationFailedException.class)) {
      return AUTHENTICATION;
    }
    boolean connecting =
        chain.stream().anyMatch(t -> t.getClass().getSimpleName().equals("MailConnectException"));
    if (connecting && any(chain, SocketTimeoutException.class)) {
      return "No connection to "
          + host
          + ":"
          + port
          + " within "
          + timings.connect().toSeconds()
          + " s.";
    }
    if (any(chain, SocketTimeoutException.class)) {
      return "No answer within " + timings.smtp().toSeconds() + " s.";
    }
    if (any(chain, UnknownHostException.class)) {
      return "Host " + host + " not found.";
    }
    if (any(chain, ConnectException.class)) {
      return "Could not connect to " + host + ":" + port + ".";
    }
    String message = failure.getMessage();
    if (message == null || message.isEmpty()) {
      message = failure.getClass().getSimpleName();
    }
    for (String value : hidden) {
      if (message.contains(value)) {
        message = failure.getClass().getSimpleName();
        break;
      }
    }
    if (message.codePointCount(0, message.length()) > REFUSAL_MAX) {
      message = message.substring(0, message.offsetByCodePoints(0, REFUSAL_MAX));
    }
    return "The SMTP server refused the message: " + message;
  }

  private static boolean any(List<Throwable> chain, Class<? extends Throwable> type) {
    return chain.stream().anyMatch(type::isInstance);
  }

  /** {@code smtp://{host}:{port}} with a port from 1 to 65535, else empty. */
  static Optional<URI> server(String address) {
    URI uri;
    try {
      uri = new URI(address);
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
    boolean valid =
        "smtp".equals(uri.getScheme())
            && uri.getHost() != null
            && uri.getRawUserInfo() == null
            && uri.getPort() >= 1
            && uri.getPort() <= PORT_MAX
            && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null;
    return valid ? Optional.of(uri) : Optional.empty();
  }

  private static boolean starttls(ConnectorInstanceView instance) {
    JsonNode value = instance.settings() == null ? null : instance.settings().get("starttls");
    if (value == null || !value.isBoolean()) {
      throw new IllegalStateException(
          "connector instance " + instance.id() + " has no starttls setting");
    }
    return value.booleanValue();
  }
}
