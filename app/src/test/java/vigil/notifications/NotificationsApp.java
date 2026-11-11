package vigil.notifications;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditEventView;
import vigil.audit.api.AuditQuery;
import vigil.audit.api.AuditService;
import vigil.connectors.Recordings;
import vigil.notifications.api.NotificationRecordView;
import vigil.notifications.api.NotificationsService;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

/**
 * The servers of the notifications tests: WireMock for the Teams webhooks ({@code /teams/{key}},
 * 202 with an empty body as recorded) and Jira, GreenMail for SMTP with a generated user, and
 * vigil-app with the secrets of {@link NotificationsFixture#secrets}.
 */
public final class NotificationsApp implements AutoCloseable {

  public final WireMockServer wiremock;
  public final GreenMail greenMail;
  public final VigilTestApp app;
  public final TestSecrets secrets;
  public final String base;
  public final int smtpPort;
  public final String smtpUser;
  public final String smtpPassword;
  private int tenants;

  private NotificationsApp(Class<?>... configurations) {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    base = "http://127.0.0.1:" + wiremock.port();
    smtpPort = freePort();
    greenMail = new GreenMail(new ServerSetup(smtpPort, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
    greenMail.start();
    smtpUser = "smtp-" + TestSecrets.randomToken().substring(0, 10).toLowerCase();
    smtpPassword = TestSecrets.randomToken();
    greenMail.setUser(smtpUser + "@vigil.test", smtpUser, smtpPassword);
    VigilDatabase database = VigilDatabase.fresh();
    secrets =
        TestSecrets.create(database, NotificationsFixture.secrets(base, smtpUser, smtpPassword));
    app = VigilTestApp.start(database, secrets.folder(), List.of(), configurations);
    app.awaitStarted();
    reset();
  }

  /** Starts the servers and vigil-app with extra configuration classes. */
  public static NotificationsApp start(Class<?>... configurations) {
    return new NotificationsApp(configurations);
  }

  /** A new tenant of {@link NotificationsFixture} with an alias. */
  public NotificationsFixture tenant(String name) {
    tenants++;
    return NotificationsFixture.create(
        app, name + " " + tenants, "n" + tenants + ".notifications.test", base, smtpPort);
  }

  /** Every Teams webhook answers 202 with an empty body; no request and no email is kept. */
  public void reset() {
    wiremock.resetAll();
    teamsAnswer(202);
    try {
      greenMail.purgeEmailFromAllMailboxes();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void teamsAnswer(int status) {
    wiremock.stubFor(post(urlPathMatching("/teams/.*")).willReturn(aResponse().withStatus(status)));
  }

  /** The Teams posts in the order received. */
  public List<LoggedRequest> teamsPosts() {
    List<LoggedRequest> posts =
        new ArrayList<>(wiremock.findAll(postRequestedFor(urlPathMatching("/teams/.*"))));
    posts.sort(Comparator.comparing(LoggedRequest::getLoggedDate));
    return posts;
  }

  /** The key of each Teams post's path, in order. */
  public List<String> teamsKeys() {
    return teamsPosts().stream()
        .map(p -> p.getUrl().replaceFirst("^/teams/([^?]*).*$", "$1"))
        .toList();
  }

  /** The texts of a Teams post's TextBlocks. */
  public static List<String> teamsTexts(LoggedRequest post) {
    List<String> texts = new ArrayList<>();
    JsonNode body =
        Recordings.tree(post.getBodyAsString())
            .get("attachments")
            .get(0)
            .get("content")
            .get("body");
    body.forEach(block -> texts.add(block.get("text").stringValue()));
    return texts;
  }

  public List<MimeMessage> emails() {
    return List.of(greenMail.getReceivedMessages());
  }

  /** The recipient of each email, in order. */
  public List<String> emailRecipients() {
    List<String> recipients = new ArrayList<>();
    for (MimeMessage message : emails()) {
      try {
        recipients.add(message.getAllRecipients()[0].toString());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    return recipients;
  }

  public List<NotificationRecordView> records(NotificationsFixture f, UUID incident) {
    return app.context().getBean(NotificationsService.class).records(f.tenant, incident);
  }

  /** The tenant's audit events of the type, newest first (at most 50). */
  public List<AuditEventView> events(NotificationsFixture f, AuditEventType type) {
    return app.context()
        .getBean(AuditService.class)
        .query(f.tenant, new AuditQuery(null, null, null, Set.of(type), null, null, null, 50, null))
        .items();
  }

  /** The values of the Teams webhook secrets, their paths and queries, and the SMTP password. */
  public List<String> hidden() {
    List<String> hidden = new ArrayList<>();
    for (String[] address : NotificationsFixture.TEAMS_ADDRESSES) {
      String value = secrets.value(NotificationsFixture.secretName(address[0], address[1]));
      hidden.add(value);
      hidden.add(value.substring(value.indexOf("/teams/"), value.indexOf('?')) + "?");
      hidden.add(value.substring(value.indexOf('?') + 1));
      hidden.add(value.substring(value.indexOf("sig=") + 4));
    }
    hidden.add(smtpPassword);
    return hidden;
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    app.close();
    wiremock.stop();
    greenMail.stop();
  }
}
