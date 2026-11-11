package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.ConnectorsPart.ConnectorInstancePart;
import vigil.connectors.api.ConnectorsPart.CredentialReferencePart;
import vigil.connectors.api.Delivery;
import vigil.connectors.api.MessageLine;
import vigil.connectors.api.NotificationChannelConnector;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class EmailConnectorIT {

  private static final Path RECORDING =
      Path.of("src/test/resources/recordings/email/0001-send-noc-l1-1.json");
  private static final String FROM = "techfinity-demo@vigil.test";
  private static final String TO = "noc-l1@vigil.test";
  private static final String SUBJECT =
      "Demo environment — fictional data - [P1] INC-0007 Subscriber DB server: Disk full";
  private static GreenMail greenMail;
  private static VigilTestApp app;
  private static TenantId tenantId;
  private static Map<String, UUID> ids;
  private static NotificationChannelConnector email;
  private static String user;
  private static String password;
  private static int port;
  private static int closedPort;
  private static List<String> recorded;

  @BeforeAll
  static void start() throws Exception {
    port = freePort();
    closedPort = freePort();
    greenMail = new GreenMail(new ServerSetup(port, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
    greenMail.start();
    user = "smtp-" + TestSecrets.randomToken().substring(0, 10).toLowerCase();
    password = TestSecrets.randomToken();
    greenMail.setUser(user + "@vigil.test", user, password);
    JsonNode recording = Recordings.tree(Files.readString(RECORDING, StandardCharsets.UTF_8));
    recorded = List.of(recording.get("request").get("body").stringValue().split("\r\n"));
    Map<String, String> secrets = new LinkedHashMap<>();
    secrets.put("smtp", "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}");
    secrets.put(
        "smtp-wrong",
        "{\"username\":\"" + user + "\",\"password\":\"" + TestSecrets.randomToken() + "\"}");
    secrets.put("smtp-bad", "{\"username\":\"" + user + "\"}");
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets folder = TestSecrets.create(database, secrets);
    app = VigilTestApp.start(database, folder.folder(), List.of());
    app.awaitStarted();
    tenantId = new TenantId(Ids.newId());
    String settings = "{\"from_address\":\"" + FROM + "\",\"starttls\":false}";
    String server = "smtp://127.0.0.1:" + port;
    List<CredentialReferencePart> references =
        List.of(
            TestParts.credential("cred-smtp", "smtp", "secret:smtp"),
            TestParts.credential("cred-wrong", "smtp", "secret:smtp-wrong"),
            TestParts.credential("cred-bad", "smtp", "secret:smtp-bad"),
            TestParts.credential("cred-missing", "smtp", "secret:smtp-gone"));
    List<ConnectorInstancePart> instances =
        List.of(
            TestParts.connector("email-demo", "email", server, settings, "cred-smtp"),
            TestParts.connector("email-wrong", "email", server, settings, "cred-wrong"),
            TestParts.connector("email-bad", "email", server, settings, "cred-bad"),
            TestParts.connector("email-missing", "email", server, settings, "cred-missing"),
            TestParts.connector(
                "email-closed", "email", "smtp://127.0.0.1:" + closedPort, settings, "cred-smtp"),
            TestParts.connector(
                "email-http", "email", "http://127.0.0.1:" + port, settings, "cred-smtp"),
            TestParts.connector(
                "email-tls",
                "email",
                server,
                "{\"from_address\":\"" + FROM + "\",\"starttls\":true}",
                "cred-smtp"));
    ids =
        app.context()
            .getBean(ConnectorImport.class)
            .importPart(
                tenantId, new ConnectorsPart(references, instances), Instant.now(), Map.of());
    email =
        app.context().getBeansOfType(NotificationChannelConnector.class).values().stream()
            .filter(bean -> bean.kind() == ConnectorKind.EMAIL)
            .findFirst()
            .orElseThrow();
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @AfterAll
  static void stop() {
    app.close();
    greenMail.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    greenMail.purgeEmailFromAllMailboxes();
  }

  /** The recorded body's lines with the first replaced by the subject: line 4 is a link. */
  private static List<MessageLine> lines() {
    List<MessageLine> lines = new ArrayList<>();
    lines.add(MessageLine.of(SUBJECT));
    lines.add(MessageLine.of(recorded.get(1)));
    lines.add(MessageLine.of(recorded.get(2)));
    String link = recorded.get(3).substring("Acknowledge: ".length());
    lines.add(new MessageLine(recorded.get(3), link));
    return lines;
  }

  private static Delivery send(String handle) {
    ConnectorInstanceView instance =
        app.context()
            .getBean(ConnectorService.class)
            .instance(tenantId, ids.get(handle))
            .orElseThrow();
    return email.send(tenantId, instance, TO, null, "Techfinity", lines());
  }

  private static String raw(MimeMessage message) throws Exception {
    return new String(message.getRawInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("AR-K-20 one email: From, To, Subject, text/plain UTF-8, 8bit, CRLF lines whole")
  void oneEmail() throws Exception {
    assertThat(send("email-demo")).isEqualTo(new Delivery.Sent());
    MimeMessage[] received = greenMail.getReceivedMessages();
    assertThat(received).hasSize(1);
    MimeMessage message = received[0];
    assertThat(message.getHeader("From", null))
        .isEqualTo("Techfinity <techfinity-demo@vigil.test>");
    assertThat(message.getHeader("To", null)).isEqualTo(TO);
    assertThat(message.getSubject()).isEqualTo(SUBJECT);
    assertThat(message.getHeader("Content-Type", null))
        .isEqualToIgnoringCase("text/plain; charset=UTF-8");
    assertThat(message.getHeader("Content-Transfer-Encoding", null)).isEqualTo("8bit");
    assertThat(message.getHeader("Date", null)).isNotNull();
    String body = raw(message);
    String expected =
        String.join("\r\n", lines().stream().map(MessageLine::text).toList()) + "\r\n";
    assertThat(EmailConnector.body(lines())).isEqualTo(expected);
    // The body's last CRLF is the first of SMTP's end-of-data marker, which GreenMail removes.
    assertThat(body + "\r\n").isEqualTo(expected);
    assertThat(recorded.get(3)).hasSize(97);
    assertThat(body.split("\r\n")[3]).isEqualTo(recorded.get(3));
    assertThat(body).contains("Demo environment — fictional data");
  }

  @Test
  @DisplayName("AR-K-06 email: smtp address, from_address, starttls and the SMTP credential")
  void instanceSettings() {
    assertThat(send("email-wrong")).isEqualTo(new Delivery.Failed("SMTP authentication failed."));
    assertThat(send("email-http"))
        .isEqualTo(new Delivery.Failed("The address is not a valid smtp address."));
    assertThat(send("email-missing"))
        .isEqualTo(new Delivery.Failed("Secret smtp-gone is not available."));
    assertThat(send("email-bad")).isEqualTo(new Delivery.Failed("Secret smtp-bad is not valid."));
    assertThat(send("email-closed"))
        .isEqualTo(new Delivery.Failed("Could not connect to 127.0.0.1:" + closedPort + "."));
    Delivery tls = send("email-tls");
    assertThat(tls).isInstanceOf(Delivery.Failed.class);
    assertThat(((Delivery.Failed) tls).reason()).doesNotContain(password);
    assertThat(greenMail.getReceivedMessages()).isEmpty();
    assertThat(send("email-demo")).isEqualTo(new Delivery.Sent());
    assertThat(greenMail.getReceivedMessages()).hasSize(1);
  }

  @Test
  @DisplayName("AR-T-14 email is sent with Jakarta Mail from Spring Boot's mail starter")
  void jakartaMail() throws Exception {
    assertThat(
            Class.forName(
                "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration"))
        .isNotNull();
    assertThat(jakarta.mail.Session.class.getName()).isEqualTo("jakarta.mail.Session");
    assertThat(send("email-demo")).isEqualTo(new Delivery.Sent());
    MimeMessage message = greenMail.getReceivedMessages()[0];
    assertThat(message.getHeader("MIME-Version", null)).isEqualTo("1.0");
    assertThat(message.getMessageID()).isNotBlank();
  }

  @Test
  @DisplayName("PM-K-06 the email connector sends one email per call to the address")
  void emailConnector() throws Exception {
    assertThat(email.kind()).isEqualTo(ConnectorKind.EMAIL);
    assertThat(send("email-demo")).isEqualTo(new Delivery.Sent());
    assertThat(send("email-demo")).isEqualTo(new Delivery.Sent());
    MimeMessage[] received = greenMail.getReceivedMessages();
    assertThat(received).hasSize(2);
    for (MimeMessage message : received) {
      assertThat(message.getAllRecipients()).hasSize(1);
      assertThat(message.getAllRecipients()[0].toString()).isEqualTo(TO);
    }
  }
}
