package vigil.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import vigil.recovery.api.ConditionKind;
import vigil.recovery.api.RecoveryCheckView;
import vigil.recovery.api.RecoveryCondition;
import vigil.recovery.api.RecoveryImport;
import vigil.recovery.api.RecoveryPart;
import vigil.recovery.api.RecoveryService;
import vigil.setup.SeedPartImporter.PartResult;
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
class RecoveryImportIT {

  static final String OPERATIONS = "04-operations-actions-recovery.yaml";
  static final String PREFIX = "Part recovery refused. recovery_check_definitions";
  static final String PROBE_CONDITION =
      "{kind: component_no_firing_alert, component_key: ussd-probe, alert_types: []}";
  static final String DEFAULT_CONDITIONS =
      "    conditions:\n      - {kind: incident_alerts_resolved}\n    observation_window_s: 60\n";

  private VigilDatabase database;
  private VigilTestApp app;
  private SeedImports seedImports;
  private RecoveryService recovery;

  @BeforeAll
  void start() {
    database = VigilDatabase.fresh();
    TestSecrets secrets = SeedTests.secrets(database);
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    seedImports = app.context().getBean(SeedImports.class);
    recovery = app.context().getBean(RecoveryService.class);
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private SeedImportView imported(byte[] bundle) {
    TenantId tenant =
        app.context().getBean(Installation.class).createTenant("recovery " + UUID.randomUUID());
    return SeedTests.await(seedImports, seedImports.start(tenant, bundle).id());
  }

  private SeedImportView changed(UnaryOperator<String> change) {
    return imported(SeedTests.demoBundleWith(OPERATIONS, change));
  }

  private static PartView part(SeedImportView view, int number) {
    return view.parts().get(number - 1);
  }

  private void assertRefused(SeedImportView view, String error) {
    assertThat(view.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(part(view, 13).outcome()).isEqualTo(PartOutcome.FAILED);
    assertThat(part(view, 13).error()).isEqualTo(error);
    assertThat(rows(view.tenantId())).isZero();
  }

  private long rows(TenantId tenant) {
    return postgres()
        .sql("SELECT count(*) FROM recovery.recovery_check_definition WHERE tenant_id = :t")
        .param("t", tenant.value())
        .query(Long.class)
        .single();
  }

  private JdbcClient postgres() {
    return database.postgres();
  }

  private SeedImportView demo() {
    SeedImportView view = imported(SeedTests.demoBundle());
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    return view;
  }

  @Test
  @DisplayName("DM-E-61 the two demo definitions with their conditions, windows and default")
  void demoDefinitions() {
    SeedImportView view = demo();
    List<RecoveryCheckView> definitions = recovery.definitions(view.tenantId());
    assertThat(definitions)
        .extracting(RecoveryCheckView::recoveryCheckKey)
        .containsExactly("rc-default", "rc-subscriber-db");
    RecoveryCheckView fallback = definitions.get(0);
    assertThat(fallback.name()).isEqualTo("All incident alerts resolved");
    assertThat(fallback.conditions())
        .containsExactly(
            new RecoveryCondition(ConditionKind.INCIDENT_ALERTS_RESOLVED, null, List.of()));
    assertThat(fallback.observationWindow()).isEqualTo(Duration.ofSeconds(60));
    assertThat(fallback.isDefault()).isTrue();
    assertThat(fallback.active()).isTrue();
    RecoveryCheckView subscriber = definitions.get(1);
    assertThat(subscriber.name()).isEqualTo("Subscriber DB services recovered");
    assertThat(subscriber.conditions()).hasSize(2);
    assertThat(subscriber.observationWindow()).isEqualTo(Duration.ofSeconds(60));
    assertThat(subscriber.isDefault()).isFalse();
    assertThat(subscriber.active()).isTrue();
  }

  @Test
  @DisplayName("DM-E61-R2 rc-subscriber-db: the incident's alerts and ussd-probe, any alert type")
  void demoConditions() {
    SeedImportView view = demo();
    assertThat(recovery.definition(view.tenantId(), "rc-subscriber-db").orElseThrow().conditions())
        .containsExactly(
            new RecoveryCondition(ConditionKind.INCIDENT_ALERTS_RESOLVED, null, List.of()),
            new RecoveryCondition(
                ConditionKind.COMPONENT_NO_FIRING_ALERT, "ussd-probe", List.of()));
  }

  @Test
  @DisplayName("PM-M15-01 recovery check definitions are kept as data and read back")
  void definitionsAsData() {
    SeedImportView view = demo();
    List<Map<String, Object>> stored =
        postgres()
            .sql(
                "SELECT recovery_check_key, name, conditions::text AS conditions,"
                    + " observation_window_seconds, is_default, active"
                    + " FROM recovery.recovery_check_definition WHERE tenant_id = :t"
                    + " ORDER BY recovery_check_key")
            .param("t", view.tenantId().value())
            .query()
            .listOfRows();
    assertThat(stored).hasSize(2);
    assertThat(stored.get(1).get("recovery_check_key")).isEqualTo("rc-subscriber-db");
    assertThat(SeedYaml.read((String) stored.get(1).get("conditions")))
        .isEqualTo(
            SeedYaml.read(
                "[{\"kind\": \"incident_alerts_resolved\"}, {\"kind\":"
                    + " \"component_no_firing_alert\", \"component_key\": \"ussd-probe\","
                    + " \"alert_types\": []}]"));
    assertThat(stored.get(1).get("observation_window_seconds")).isEqualTo(60L);
    assertThat(stored.get(0).get("is_default")).isEqualTo(true);
    assertThat(stored).allSatisfy(row -> assertThat(row.get("active")).isEqualTo(true));
    assertThat(recovery.definitions(view.tenantId()))
        .extracting(RecoveryCheckView::name)
        .containsExactly("All incident alerts resolved", "Subscriber DB services recovered");
  }

  @Test
  @DisplayName(
      "DD-F-04 recovery_check_definitions of 04-operations-actions-recovery.yaml is part 13")
  void section() {
    SeedImportView view = demo();
    assertThat(part(view, 13).module()).isEqualTo("vigil-recovery");
    assertThat(part(view, 13).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(part(view, 13).counts()).isEqualTo(Map.of("recovery_check_definitions", 2));
  }

  @Test
  @DisplayName("DM-V-07 a condition of each kind with its fields; alert types stored sorted")
  void conditionKinds() {
    SeedImportView view =
        changed(
            SeedTests.replacing(
                PROBE_CONDITION,
                "{kind: component_no_firing_alert, component_key: ussd-probe,"
                    + " alert_types: [ussd_timeouts, ussd_synthetic_failed]}"));
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    List<RecoveryCondition> conditions =
        recovery.definition(view.tenantId(), "rc-subscriber-db").orElseThrow().conditions();
    assertThat(conditions.get(0).kind()).isEqualTo(ConditionKind.INCIDENT_ALERTS_RESOLVED);
    assertThat(conditions.get(0).componentKey()).isNull();
    assertThat(conditions.get(0).alertTypes()).isEmpty();
    assertThat(conditions.get(1).kind()).isEqualTo(ConditionKind.COMPONENT_NO_FIRING_ALERT);
    assertThat(conditions.get(1).componentKey()).isEqualTo("ussd-probe");
    assertThat(conditions.get(1).alertTypes())
        .containsExactly("ussd_synthetic_failed", "ussd_timeouts");
    assertThatThrownBy(
            () -> new RecoveryCondition(ConditionKind.INCIDENT_ALERTS_RESOLVED, "x", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("recovery condition: kind, component and alert types");
    assertThatThrownBy(
            () -> new RecoveryCondition(ConditionKind.COMPONENT_NO_FIRING_ALERT, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("DM-E61-R1 exactly one default definition")
  void oneDefault() {
    assertRefused(
        changed(SeedTests.replacing("is_default: true", "is_default: false")),
        "Part recovery refused. recovery_check_definitions: exactly one default");
    assertRefused(
        changed(SeedTests.replacing("is_default: false", "is_default: true")),
        "Part recovery refused. recovery_check_definitions: exactly one default");
  }

  @Test
  @DisplayName("DM-R-56 parts 13 and 17 succeed in DM-R-56 order in the demo import")
  void partsInOrder() {
    SeedImportView view = demo();
    assertThat(view.parts().stream().map(PartView::module).toList().subList(12, 17))
        .containsExactly(
            "vigil-recovery",
            "vigil-actions",
            "vigil-notifications",
            "vigil-workflows",
            "vigil-reporting");
    assertThat(part(view, 13).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(part(view, 17).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(view.parts())
        .allSatisfy(p -> assertThat(p.outcome()).isNotEqualTo(PartOutcome.FAILED));
  }

  @Test
  @DisplayName("DM-R-57 part 13 is checked as a whole and refused with every problem")
  void refusals() {
    List<String[]> cases =
        List.of(
            new String[] {
              "  - recovery_check_key: rc-subscriber-db",
              "  - recovery_check_key: \"rc subscriber\"",
              "[0].recovery_check_key: not a key"
            },
            new String[] {
              "  - recovery_check_key: rc-default",
              "  - recovery_check_key: rc-subscriber-db",
              "[1].recovery_check_key: used more than once"
            },
            new String[] {
              "name: \"All incident alerts resolved\"",
              "name: \"\"",
              "[1].name: 1 to 100 characters"
            },
            new String[] {
              DEFAULT_CONDITIONS,
              "    conditions: []\n    observation_window_s: 60\n",
              "[1].conditions: at least one condition"
            },
            new String[] {
              DEFAULT_CONDITIONS,
              "    conditions:\n      - incident_alerts_resolved\n    observation_window_s: 60\n",
              "[1].conditions[0]: not a mapping"
            },
            new String[] {
              PROBE_CONDITION,
              "{kind: component_no_firing_alert, component_key: ussd-probe, alert_types: [],"
                  + " severity: high}",
              "[0].conditions[1]: unknown field severity"
            },
            new String[] {
              PROBE_CONDITION,
              "{kind: component_down, component_key: ussd-probe}",
              "[0].conditions[1].kind: one of incident_alerts_resolved, component_no_firing_alert"
            },
            new String[] {
              DEFAULT_CONDITIONS,
              "    conditions:\n      - {kind: incident_alerts_resolved, component_key: db}\n"
                  + "    observation_window_s: 60\n",
              "[1].conditions[0].component_key: not allowed for incident_alerts_resolved"
            },
            new String[] {
              DEFAULT_CONDITIONS,
              "    conditions:\n      - {kind: incident_alerts_resolved, alert_types: [disk_full]}\n"
                  + "    observation_window_s: 60\n",
              "[1].conditions[0].alert_types: not allowed for incident_alerts_resolved"
            },
            new String[] {
              PROBE_CONDITION,
              "{kind: component_no_firing_alert, component_key: \"ussd probe\"}",
              "[0].conditions[1].component_key: not a key"
            },
            new String[] {
              PROBE_CONDITION,
              "{kind: component_no_firing_alert, component_key: ussd-probe, alert_types: disk_full}",
              "[0].conditions[1].alert_types: not a list"
            },
            new String[] {
              PROBE_CONDITION,
              "{kind: component_no_firing_alert, component_key: ussd-probe,"
                  + " alert_types: [\"disk full\"]}",
              "[0].conditions[1].alert_types[0]: not a key"
            },
            new String[] {
              PROBE_CONDITION,
              "{kind: component_no_firing_alert, component_key: ussd-probe,"
                  + " alert_types: [disk_full, disk_full]}",
              "[0].conditions[1].alert_types[1]: used more than once"
            },
            new String[] {
              "observation_window_s: 60                        # SL-O-01",
              "observation_window_s: 0",
              "[0].observation_window_s: 1 to 86400 seconds"
            },
            new String[] {
              "observation_window_s: 60                        # SL-O-01",
              "observation_window_s: 86401",
              "[0].observation_window_s: 1 to 86400 seconds"
            },
            new String[] {
              "observation_window_s: 60                        # SL-O-01",
              "observation_window_s: 1.5",
              "[0].observation_window_s: 1 to 86400 seconds"
            },
            new String[] {
              "is_default: false", "is_default: maybe", "[0].is_default: true or false"
            });
    for (String[] c : cases) {
      assertRefused(changed(SeedTests.replacing(c[0], c[1])), PREFIX + c[2]);
    }

    SeedImportView whole =
        changed(
            text ->
                SeedTests.replacing("name: \"All incident alerts resolved\"", "name: \"\"")
                    .andThen(
                        SeedTests.replacing(
                            "observation_window_s: 60                        # SL-O-01",
                            "observation_window_s: 0"))
                    .apply(text));
    assertRefused(
        whole,
        PREFIX
            + "[0].observation_window_s: 1 to 86400 seconds; recovery_check_definitions"
            + "[1].name: 1 to 100 characters");

    SeedImportView ok = demo();
    RecoveryImport importer = app.context().getBean(RecoveryImport.class);
    assertThatThrownBy(
            () ->
                importer.importPart(
                    ok.tenantId(), new RecoveryPart(List.of()), Instant.now(), Map.of()))
        .isInstanceOfSatisfying(
            VigilError.class,
            error -> {
              assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
              assertThat(error.message()).isEqualTo("Part recovery refused.");
              assertThat(error.details().get("problems"))
                  .isEqualTo(List.of("recovery check definitions already exist"));
            });
    assertThat(rows(ok.tenantId())).isEqualTo(2);
  }

  @Test
  @DisplayName("DM-R-60 the components and alert types of part 13 are checked against parts 5, 6")
  void keyChecks() {
    SeedImportView view =
        changed(
            SeedTests.replacing(
                PROBE_CONDITION,
                "{kind: component_no_firing_alert, component_key: srv-nowhere}\n"
                    + "      - {kind: component_no_firing_alert, component_key: ussd-probe,"
                    + " alert_types: [nowhere]}"));
    assertThat(view.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(part(view, 13).outcome()).isEqualTo(PartOutcome.FAILED);
    assertThat(part(view, 13).error())
        .startsWith("Key check failed: ")
        .contains("recovery check 'rc-subscriber-db': component 'srv-nowhere' does not exist")
        .contains("recovery check 'rc-subscriber-db': alert type 'nowhere' does not exist");
  }

  @Test
  @DisplayName("AR-B-11 part 13 passes the handle map on unchanged, with its counts")
  void importInterface() {
    SeedImportView view = demo();
    assertThat(part(view, 13).counts()).isEqualTo(Map.of("recovery_check_definitions", 2));
    TenantId tenant =
        app.context().getBean(Installation.class).createTenant("recovery " + UUID.randomUUID());
    Map<String, UUID> handles = new HashMap<>(Map.of("cred-x", UUID.randomUUID()));
    Map<String, UUID> before = Map.copyOf(handles);
    Map<String, JsonNode> files =
        Map.of(
            SeedParts.OPERATIONS,
            SeedYaml.read(SeedTests.text(SeedTests.demoEntries(), SeedParts.OPERATIONS)));
    PartResult result =
        app.context()
            .getBean(RecoveryAdapter.class)
            .importPart(tenant, null, files, Instant.now(), handles);
    assertThat(result.counts()).isEqualTo(Map.of("recovery_check_definitions", 2));
    assertThat(result.handles()).isEmpty();
    assertThat(handles).isEqualTo(before);
    assertThat(recovery.definitions(tenant)).hasSize(2);
  }
}
