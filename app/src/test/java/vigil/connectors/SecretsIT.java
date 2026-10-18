package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.Secrets;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@ExtendWith(OutputCaptureExtension.class)
class SecretsIT {

  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  private static VigilTestApp app;
  private static TestSecrets secrets;
  private static Secrets store;
  private static TransactionTemplate transaction;
  private static TenantId tenantA;
  private static Map<String, UUID> ids;
  private static String diagFirst;
  private static String diagSecond;

  @BeforeAll
  static void start() {
    VigilDatabase database = VigilDatabase.fresh();
    diagFirst = TestSecrets.randomToken();
    diagSecond = TestSecrets.randomToken();
    StringBuilder password = new StringBuilder();
    SecureRandom random = new SecureRandom();
    for (int i = 0; i < 20; i++) {
      password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    secrets =
        TestSecrets.create(
            database,
            Map.of(
                "webhook-a", TestSecrets.randomToken(),
                "diag-key", diagFirst + "\n" + diagSecond,
                "seed-password-noc-l1", password.toString(),
                "installation-token", TestSecrets.randomToken()));
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    store = app.context().getBean(Secrets.class);
    transaction = new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
    tenantA = new TenantId(Ids.newId());
    ids =
        app.context()
            .getBean(ConnectorImport.class)
            .importPart(
                tenantA,
                new ConnectorsPart(
                    List.of(
                        TestParts.credential("w", "webhook_token", "secret:webhook-a"),
                        TestParts.credential("d", "target_diagnostic", "secret:diag-key"),
                        TestParts.credential("a", "target_action", "secret:missing-action"),
                        TestParts.credential("x", "external_api", "secret:webhook-a"),
                        TestParts.credential("m", "webhook_token", "secret:missing-webhook")),
                    List.of()),
                Instant.now(),
                Map.of());
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  private static String otherLast(String value) {
    char last = value.charAt(value.length() - 1) == 'A' ? 'B' : 'A';
    return value.substring(0, value.length() - 1) + last;
  }

  @Test
  @DisplayName("AR-S-05 webhook tokens verify only for a webhook_token reference of the tenant")
  void verify() {
    String token = secrets.value("webhook-a");
    UUID w = ids.get("w");
    assertThat(store.verify(tenantA, w, token)).isTrue();
    assertThat(store.verify(tenantA, w, otherLast(token))).isFalse();
    assertThat(store.verify(tenantA, w, token + "A")).isFalse();
    assertThat(store.verify(tenantA, w, "")).isFalse();
    assertThat(store.verify(tenantA, w, null)).isFalse();
    assertThat(store.verify(tenantA, ids.get("x"), token)).isFalse();
    assertThat(store.verify(tenantA, ids.get("m"), token)).isFalse();
    assertThat(store.verify(tenantA, Ids.newId(), token)).isFalse();
    assertThat(store.verify(new TenantId(Ids.newId()), w, token)).isFalse();
  }

  @Test
  @DisplayName("AR-S-04 secret files are read at each use by the four Secrets operations")
  void readAtEachUse() {
    String old = secrets.value("webhook-a");
    String fresh = TestSecrets.randomToken();
    secrets.replace("webhook-a", fresh);
    try {
      assertThat(store.verify(tenantA, ids.get("w"), fresh)).isTrue();
      assertThat(store.verify(tenantA, ids.get("w"), old)).isFalse();
    } finally {
      secrets.replace("webhook-a", old);
    }
    assertThat(store.read(tenantA, ids.get("d"))).isEqualTo(diagFirst + "\n" + diagSecond);
    assertThatThrownBy(() -> store.read(tenantA, ids.get("w")))
        .isInstanceOf(IllegalArgumentException.class);
    UUID unknown = Ids.newId();
    assertThatThrownBy(() -> store.read(tenantA, unknown))
        .isInstanceOfSatisfying(
            VigilError.class,
            e -> {
              assertThat(e.code()).isEqualTo(VigilError.Code.NOT_FOUND);
              assertThat(e.message()).isEqualTo("Credential reference " + unknown + " not found.");
            });
    assertThatThrownBy(() -> store.read(tenantA, ids.get("a")))
        .isInstanceOfSatisfying(
            VigilError.class,
            e -> {
              assertThat(e.code()).isEqualTo(VigilError.Code.DEPENDENCY_FAILED);
              assertThat(e.message()).isEqualTo("Secret missing-action is not available.");
            });
    assertThat(store.readSeedPassword("noc-l1")).isEqualTo(secrets.value("seed-password-noc-l1"));
    assertThatThrownBy(() -> store.readSeedPassword("Admin"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.readSeedPassword("dbops-lead"))
        .isInstanceOfSatisfying(
            VigilError.class,
            e -> {
              assertThat(e.code()).isEqualTo(VigilError.Code.DEPENDENCY_FAILED);
              assertThat(e.message())
                  .isEqualTo("Secret seed-password-dbops-lead is not available.");
            });
    Boolean verifiedInside =
        transaction.execute(
            status -> store.verify(tenantA, ids.get("w"), secrets.value("webhook-a")));
    assertThat(verifiedInside).isTrue();
    String readInside = transaction.execute(status -> store.read(tenantA, ids.get("d")));
    assertThat(readInside).isEqualTo(diagFirst + "\n" + diagSecond);
  }

  @Test
  @DisplayName("BP-A-10 the installation token verifies in constant time")
  void installationToken() {
    String token = secrets.value("installation-token");
    assertThat(store.verifyInstallationToken(token)).isTrue();
    assertThat(store.verifyInstallationToken(otherLast(token))).isFalse();
    assertThat(store.verifyInstallationToken("")).isFalse();
    assertThat(store.verifyInstallationToken(null)).isFalse();
    secrets.remove("installation-token");
    try {
      assertThat(store.verifyInstallationToken(token)).isFalse();
    } finally {
      secrets.replace("installation-token", token);
    }
  }

  @Test
  @DisplayName("AR-S-11 no secret value in a log line of the secret operations")
  void noSecretInLogs(CapturedOutput output) {
    String wrong = TestSecrets.randomToken();
    store.verify(tenantA, ids.get("w"), secrets.value("webhook-a"));
    store.verify(tenantA, ids.get("w"), wrong);
    store.read(tenantA, ids.get("d"));
    assertThatThrownBy(() -> store.read(tenantA, ids.get("a"))).isInstanceOf(VigilError.class);
    store.readSeedPassword("noc-l1");
    store.verifyInstallationToken(secrets.value("installation-token"));
    LogLines.assertNoneContains(
        output.getOut(),
        List.of(
            secrets.value("webhook-a"),
            secrets.value("diag-key"),
            secrets.value("seed-password-noc-l1"),
            secrets.value("installation-token"),
            wrong,
            diagFirst,
            diagSecond));
    List<JsonNode> warnings =
        LogLines.application(output.getOut()).stream()
            .filter(line -> line.path("log").path("level").asString("").equals("WARN"))
            .filter(line -> line.path("message").asString("").contains("missing-action"))
            .toList();
    assertThat(warnings).isNotEmpty();
  }
}
