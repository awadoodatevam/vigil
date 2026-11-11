package vigil.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import vigil.notifications.api.Channel;
import vigil.notifications.api.DeliveryAddressView;
import vigil.notifications.api.NotificationsImport;
import vigil.notifications.api.NotificationsPart;
import vigil.notifications.api.NotificationsService;
import vigil.notifications.api.RecipientKind;
import vigil.setup.api.Installation;
import vigil.setup.api.PartOutcome;
import vigil.setup.api.PartView;
import vigil.setup.api.SeedImportState;
import vigil.setup.api.SeedImportView;
import vigil.setup.api.SeedImports;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationsImportIT {

  static final String SETTINGS = "07-settings.yaml";
  static final String NOC_L1_TEAMS =
      "{subject_kind: team, subject_key: noc-l1,             channel: teams,"
          + " address: \"Techfinity Demo / NOC L1\", credential_ref: cred-teams-noc-l1}";
  static final String NOC_L1_EMAIL =
      "{subject_kind: team, subject_key: noc-l1,             channel: email,"
          + " address: \"noc-l1@vigil.test\"}";
  static final List<String> MAILBOXES =
      List.of(
          "noc-l1",
          "db-ops",
          "vas-service-owners",
          "it-apps",
          "noc-shift-lead",
          "noc-management",
          "customer-care",
          "dbops-engineer",
          "dbops-lead");

  private VigilDatabase database;
  private VigilTestApp app;
  private SeedImports seedImports;

  @BeforeAll
  void start() {
    database = VigilDatabase.fresh();
    TestSecrets secrets = SeedTests.secrets(database);
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    seedImports = app.context().getBean(SeedImports.class);
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private SeedImportView imported(byte[] bundle) {
    TenantId tenant =
        app.context()
            .getBean(Installation.class)
            .createTenant("notifications " + UUID.randomUUID());
    return SeedTests.await(seedImports, seedImports.start(tenant, bundle).id());
  }

  private SeedImportView changed(UnaryOperator<String> change) {
    return imported(SeedTests.demoBundleWith(SETTINGS, change));
  }

  private static PartView part(SeedImportView view, int number) {
    return view.parts().get(number - 1);
  }

  private void assertRefused(SeedImportView view, String error) {
    assertThat(view.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(part(view, 15).outcome()).isEqualTo(PartOutcome.FAILED);
    assertThat(part(view, 15).error()).isEqualTo(error);
    assertThat(addressRows(view.tenantId())).isZero();
  }

  private long addressRows(TenantId tenant) {
    return postgres()
        .sql("SELECT count(*) FROM notifications.delivery_address WHERE tenant_id = :t")
        .param("t", tenant.value())
        .query(Long.class)
        .single();
  }

  private JdbcClient postgres() {
    return database.postgres();
  }

  private List<DeliveryAddressView> addresses(TenantId tenant) {
    return app.context().getBean(NotificationsService.class).deliveryAddresses(tenant);
  }

  @Test
  @DisplayName("DM-E-77 the eighteen delivery addresses of part 15, and each refusal")
  void deliveryAddresses() {
    SeedImportView view = imported(SeedTests.demoBundle());
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    assertThat(part(view, 15).counts()).isEqualTo(Map.of("delivery_addresses", 18));
    List<DeliveryAddressView> addresses = addresses(view.tenantId());
    assertThat(addresses).hasSize(18).allMatch(DeliveryAddressView::active);
    List<DeliveryAddressView> teams =
        addresses.stream().filter(a -> a.channel() == Channel.TEAMS).toList();
    assertThat(teams).hasSize(9);
    for (DeliveryAddressView address : teams) {
      String locator =
          postgres()
              .sql("SELECT locator FROM connectors.credential_reference WHERE id = :id")
              .param("id", address.credentialRefId())
              .query(String.class)
              .single();
      assertThat(locator)
          .isEqualTo("secret:teams-" + address.subjectKind().key() + "-" + address.subjectKey());
    }
    assertThat(teams.stream().filter(a -> a.subjectKey().equals("noc-l1")).findFirst())
        .get()
        .extracting(DeliveryAddressView::address)
        .isEqualTo("Techfinity Demo / NOC L1");
    assertThat(addresses.stream().filter(a -> a.channel() == Channel.EMAIL))
        .hasSize(9)
        .allMatch(a -> a.credentialRefId() == null)
        .allMatch(a -> a.address().equals(a.subjectKey() + "@vigil.test"));
    assertThat(addresses.stream().filter(a -> a.subjectKind() == RecipientKind.USER))
        .extracting(DeliveryAddressView::subjectKey)
        .containsExactly("dbops-engineer", "dbops-engineer", "dbops-lead", "dbops-lead");

    String prefix = "Part notifications refused. delivery_addresses";
    List<String[]> cases =
        List.of(
            new String[] {
              "{subject_kind: team, subject_key: noc-l1,             channel: teams",
              "{subject_kind: group, subject_key: noc-l1,             channel: teams",
              "[0].subject_kind: one of team, user"
            },
            new String[] {
              "subject_key: noc-l1,             channel: teams",
              "subject_key: \"noc l1\", channel: teams",
              "[0].subject_key: not a key"
            },
            new String[] {
              "subject_key: noc-l1,             channel: email",
              "subject_key: noc-l1, channel: sms",
              "[1].channel: one of teams, email"
            },
            new String[] {
              "address: \"Techfinity Demo / NOC L1\"",
              "address: \"\"",
              "[0].address: 1 to 500 characters"
            },
            new String[] {
              ", credential_ref: cred-teams-noc-l1}", "}", "[0].credential_ref: required for teams"
            },
            new String[] {
              "credential_ref: cred-teams-noc-l1}",
              "credential_ref: cred-ghost}",
              "[0].credential_ref: not a credential reference of part 3"
            },
            new String[] {
              "credential_ref: cred-teams-noc-l1}",
              "credential_ref: cred-smtp}",
              "[0].credential_ref: purpose must be webhook_url"
            },
            new String[] {
              "address: \"noc-l1@vigil.test\"}",
              "address: \"noc-l1@vigil.test\", credential_ref: cred-smtp}",
              "[1].credential_ref: not allowed for email"
            },
            new String[] {
              "address: \"noc-l1@vigil.test\"}",
              "address: \"noc-l1 at vigil.test\"}",
              "[1].address: not an email address"
            },
            new String[] {
              "subject_key: db-ops,             channel: teams",
              "subject_key: noc-l1,             channel: teams",
              "[2]: the same subject and channel as delivery_addresses[0]"
            });
    for (String[] c : cases) {
      assertRefused(changed(SeedTests.replacing(c[0], c[1])), prefix + c[2]);
    }
    assertRefused(
        changed(SeedTests.replacing(NOC_L1_TEAMS, "{subject_kind: team, sms: 1}")),
        "Part notifications refused. delivery_addresses[0]: unknown field sms");

    SeedImportView checked =
        changed(
            text ->
                SeedTests.replacing(
                        "subject_key: it-apps,            channel: teams",
                        "subject_key: ghost-team,         channel: teams")
                    .andThen(
                        SeedTests.replacing(
                            "subject_kind: user, subject_key: dbops-lead,         channel: email",
                            "subject_kind: user, subject_key: ghost-user, channel: email"))
                    .apply(text));
    assertThat(checked.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(part(checked, 15).error())
        .isEqualTo(
            "Key check failed: delivery address teams: team 'ghost-team' does not exist;"
                + " delivery address email: user 'ghost-user' does not exist");
  }

  @Test
  @DisplayName("AR-K-21 the nine mailboxes are deployment values of the email addresses")
  void mailboxes() throws Exception {
    String text =
        Files.readString(Path.of("../docs/31-demo-data/07-settings.yaml"), StandardCharsets.UTF_8);
    List<String> placeholders = new ArrayList<>();
    Matcher matcher =
        Pattern.compile("channel: email, address: \"\\$\\{mailbox\\.([^}]+)}\"").matcher(text);
    while (matcher.find()) {
      placeholders.add(matcher.group(1));
    }
    assertThat(placeholders).containsExactlyElementsOf(MAILBOXES);
    SeedImportView view = imported(SeedTests.demoBundle());
    assertThat(
            addresses(view.tenantId()).stream()
                .filter(a -> a.channel() == Channel.EMAIL)
                .map(DeliveryAddressView::address)
                .toList())
        .containsExactlyInAnyOrderElementsOf(
            MAILBOXES.stream().map(key -> key + "@vigil.test").toList());
  }

  @Test
  @DisplayName("DM-R-56 part 15 is imported in its place, after part 12 and before part 16")
  void inOrder() {
    SeedImportView view = imported(SeedTests.demoBundle());
    assertThat(view.parts().get(14).module()).isEqualTo("vigil-notifications");
    for (int number = 1; number <= 17; number++) {
      assertThat(part(view, number).outcome())
          .as("part %s", number)
          .isEqualTo(
              number <= 12 || number == 15 || number == 16
                  ? PartOutcome.SUCCEEDED
                  : PartOutcome.NOT_STARTED);
    }
    SeedImportView refused =
        changed(SeedTests.replacing(NOC_L1_EMAIL, "{subject_kind: team, subject_key: noc-l1}"));
    assertThat(part(refused, 15).outcome()).isEqualTo(PartOutcome.FAILED);
    assertThat(part(refused, 16).outcome()).isEqualTo(PartOutcome.NOT_STARTED);
    assertThat(part(refused, 12).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
  }

  @Test
  @DisplayName("DM-R-57 part 15 is checked as a whole and refused with every problem")
  void refusedAsAWhole() {
    SeedImportView view =
        changed(
            text ->
                SeedTests.replacing(
                        "subject_key: noc-l1,             channel: email",
                        "subject_key: noc-l1, channel: sms")
                    .andThen(
                        SeedTests.replacing(
                            "credential_ref: cred-teams-customer-care}",
                            "credential_ref: cred-jira}"))
                    .apply(text));
    assertRefused(
        view,
        "Part notifications refused. delivery_addresses[1].channel: one of teams, email;"
            + " delivery_addresses[12].credential_ref: purpose must be webhook_url");
    SeedImportView ok = imported(SeedTests.demoBundle());
    NotificationsImport importer = app.context().getBean(NotificationsImport.class);
    assertThatThrownBy(
            () ->
                importer.importPart(
                    ok.tenantId(), new NotificationsPart(List.of()), Instant.now(), Map.of()))
        .isInstanceOfSatisfying(
            VigilError.class,
            error -> {
              assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
              assertThat(error.message()).isEqualTo("Part notifications refused.");
              assertThat(error.details().get("problems"))
                  .isEqualTo(List.of("delivery addresses already exist"));
            });
    assertThat(addressRows(ok.tenantId())).isEqualTo(18);
  }

  @Test
  @DisplayName("DM-R-60 the teams and users of part 15's addresses are checked against part 2")
  void keyCheck() {
    SeedImportView view =
        changed(
            SeedTests.replacing(
                "subject_kind: user, subject_key: dbops-engineer,     channel: teams",
                "subject_kind: user, subject_key: ghost-user,     channel: teams"));
    assertThat(view.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(part(view, 15).outcome()).isEqualTo(PartOutcome.FAILED);
    assertThat(part(view, 15).error())
        .isEqualTo("Key check failed: delivery address teams: user 'ghost-user' does not exist");
  }
}
