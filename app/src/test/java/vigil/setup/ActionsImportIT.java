package vigil.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import tools.jackson.databind.JsonNode;
import vigil.actions.api.ActionPolicyView;
import vigil.actions.api.ActionsImport;
import vigil.actions.api.ActionsPart;
import vigil.actions.api.ActionsService;
import vigil.actions.api.PolicyOutcome;
import vigil.actions.api.RegisteredActionView;
import vigil.actions.api.StateCheck;
import vigil.actions.api.StateCheckOperator;
import vigil.setup.SeedPartImporter.PartResult;
import vigil.setup.api.Installation;
import vigil.setup.api.PartOutcome;
import vigil.setup.api.PartView;
import vigil.setup.api.SeedImportState;
import vigil.setup.api.SeedImportView;
import vigil.setup.api.SeedImports;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActionsImportIT {

  static final String OPERATIONS = "04-operations-actions-recovery.yaml";
  static final String PAST = "09-past-incidents.csv";
  static final String PREFIX = "Part actions refused. ";
  static final String POLICY =
      "  - {action_key: purge-archived-txlogs, service_key: null, outcome: approval_required}";
  static final String PRECONDITION =
      "precondition:   {operation_key: fs_used_pct, parameters: {mount: /data}, result_field:"
          + " value, operator: greater_or_equal, value: \"90\"}";

  private VigilDatabase database;
  private VigilTestApp app;
  private SeedImports seedImports;
  private ActionsService actions;

  @BeforeAll
  void start() {
    database = VigilDatabase.fresh();
    TestSecrets secrets = SeedTests.secrets(database);
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    seedImports = app.context().getBean(SeedImports.class);
    actions = app.context().getBean(ActionsService.class);
  }

  @AfterAll
  void stop() {
    app.close();
  }

  private SeedImportView imported(byte[] bundle) {
    TenantId tenant =
        app.context().getBean(Installation.class).createTenant("actions " + UUID.randomUUID());
    return SeedTests.await(seedImports, seedImports.start(tenant, bundle).id());
  }

  private SeedImportView changed(UnaryOperator<String> change) {
    return imported(SeedTests.demoBundleWith(OPERATIONS, change));
  }

  private static PartView part(SeedImportView view, int number) {
    return view.parts().get(number - 1);
  }

  private SeedImportView demo() {
    SeedImportView view = imported(SeedTests.demoBundle());
    assertThat(view.state()).isEqualTo(SeedImportState.SUCCEEDED);
    return view;
  }

  private void assertRefused(SeedImportView view, String problem) {
    assertThat(view.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(part(view, 14).outcome()).isEqualTo(PartOutcome.FAILED);
    assertThat(part(view, 14).error()).as(problem).startsWith(PREFIX).contains(problem);
    assertThat(actions.registeredActions(view.tenantId())).isEmpty();
    assertThat(actions.policies(view.tenantId())).isEmpty();
  }

  @Test
  @DisplayName("DM-E-55 the registered action with every field of the data")
  void registeredAction() {
    SeedImportView view = demo();
    List<RegisteredActionView> all = actions.registeredActions(view.tenantId());
    assertThat(all).hasSize(1);
    RegisteredActionView a = all.getFirst();
    assertThat(a.actionKey()).isEqualTo("purge-archived-txlogs");
    assertThat(a.name()).isEqualTo("Purge archived transaction logs");
    assertThat(a.operationKey()).isEqualTo("purge_old_archives");
    assertThat(SeedYaml.read(a.parameterValuesJson()))
        .isEqualTo(
            SeedYaml.read(
                "{\"path\": \"/data/txlog_archive\", \"older_than_minutes\": 1440,"
                    + " \"max_bytes\": 12884901888}"));
    assertThat(a.limits())
        .containsExactly(
            "Deletes only *.arc files directly under /data/txlog_archive",
            "Deletes only files last modified more than 24 hours ago",
            "Deletes at most 12 GiB, oldest files first",
            "Runs only on the Subscriber DB server (lab-db-01)");
    assertThat(a.attemptLimit()).isEqualTo(1);
    assertThat(a.precondition())
        .isEqualTo(
            new StateCheck(
                "fs_used_pct",
                "{\"mount\":\"/data\"}",
                "value",
                StateCheckOperator.GREATER_OR_EQUAL,
                "90"));
    assertThat(a.reconciliation())
        .isEqualTo(
            new StateCheck(
                "fs_used_pct",
                "{\"mount\":\"/data\"}",
                "value",
                StateCheckOperator.LESS_THAN,
                "85"));
    assertThat(a.recoveryCheckKey()).isEqualTo("rc-subscriber-db");
    assertThat(a.active()).isTrue();
    assertThat(a.updatedBy()).isEqualTo(Actor.module("vigil-setup"));
    assertThat(actions.registeredAction(view.tenantId(), "purge-archived-txlogs")).contains(a);
  }

  @Test
  @DisplayName("DM-E-56 the action policy: approval_required for every service")
  void policy() {
    SeedImportView view = demo();
    assertThat(actions.policies(view.tenantId()))
        .containsExactly(
            new ActionPolicyView(
                "purge-archived-txlogs", null, PolicyOutcome.APPROVAL_REQUIRED, true));
  }

  @Test
  @DisplayName("DM-E56-R1 one default policy per action; at most one per action and service")
  void onePolicy() {
    assertRefused(
        changed(
            SeedTests.replacing(
                POLICY,
                "  - {action_key: purge-archived-txlogs, service_key: svc-balance-check, outcome:"
                    + " blocked}")),
        "registered_actions[0]: exactly one policy for every service");
    assertRefused(
        changed(SeedTests.replacing(POLICY, POLICY + "\n" + POLICY)),
        "action_policies[1]: a second policy for purge-archived-txlogs and every service");
    String service =
        "  - {action_key: purge-archived-txlogs, service_key: svc-balance-check, outcome:"
            + " blocked}";
    assertRefused(
        changed(SeedTests.replacing(POLICY, POLICY + "\n" + service + "\n" + service)),
        "action_policies[2]: a second policy for purge-archived-txlogs and svc-balance-check");
  }

  @Test
  @DisplayName(
      "PM-M14-01 only a registered action: a past incident's unknown fix action is refused")
  void onlyRegistered() {
    SeedImportView view =
        imported(
            SeedTests.demoBundleWith(
                PAST, text -> text.replaceFirst("purge-archived-txlogs,4", "purge-everything,4")));
    assertThat(view.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(part(view, 8).outcome()).isEqualTo(PartOutcome.FAILED);
    assertThat(part(view, 8).error())
        .startsWith("Key check failed: ")
        .contains("past incident 'PI-1001': action 'purge-everything' does not exist");
  }

  @Test
  @DisplayName("DD-F-04 registered_actions and action_policies of 04-operations are part 14")
  void sections() {
    SeedImportView view = demo();
    assertThat(part(view, 14).module()).isEqualTo("vigil-actions");
    assertThat(part(view, 14).outcome()).isEqualTo(PartOutcome.SUCCEEDED);
    assertThat(part(view, 14).counts())
        .isEqualTo(Map.of("registered_actions", 1, "action_policies", 1));
  }

  @Test
  @DisplayName("DM-R-56 every part of the demo bundle succeeds, in DM-R-56 order")
  void everyPart() {
    SeedImportView view = demo();
    assertThat(view.parts().stream().map(PartView::module).toList())
        .containsExactly(
            "vigil-tenancy",
            "vigil-identity",
            "vigil-connectors",
            "vigil-runner",
            "vigil-servicemap",
            "vigil-intake",
            "vigil-ai-gateway",
            "vigil-knowledge",
            "vigil-incidents",
            "vigil-correlation",
            "vigil-routing",
            "vigil-diagnostics",
            "vigil-recovery",
            "vigil-actions",
            "vigil-notifications",
            "vigil-workflows",
            "vigil-reporting");
    assertThat(view.parts())
        .allSatisfy(p -> assertThat(p.outcome()).isEqualTo(PartOutcome.SUCCEEDED));
  }

  @Test
  @DisplayName("DM-R-57 part 14 is checked as a whole and refused with every problem")
  void refusals() {
    String text = SeedTests.text(SeedTests.demoEntries(), OPERATIONS);
    String block =
        text.substring(
            text.indexOf("  - action_key: purge-archived-txlogs"),
            text.indexOf("\naction_policies:"));
    List<String[]> cases =
        List.of(
            new String[] {
              "  - action_key: purge-archived-txlogs",
              "  - action_key: \"purge archived\"",
              "registered_actions[0].action_key: not a key"
            },
            new String[] {
              "\naction_policies:",
              block + "\naction_policies:",
              "registered_actions[1].action_key: used more than once"
            },
            new String[] {
              "    name: \"Purge archived transaction logs\"\n    operation_key",
              "    name: \"\"\n    operation_key",
              "registered_actions[0].name: 1 to 100 characters"
            },
            new String[] {
              "operation_key: purge_old_archives\n    parameter_values",
              "operation_key: \"purge old\"\n    parameter_values",
              "registered_actions[0].operation_key: not a key"
            },
            new String[] {
              "parameter_values: {path: /data/txlog_archive, older_than_minutes: 1440, max_bytes:"
                  + " 12884901888}",
              "parameter_values: [1]",
              "registered_actions[0].parameter_values: not a mapping"
            },
            new String[] {
              "    limits:\n      - \"Deletes only *.arc files directly under /data/txlog_archive\"\n"
                  + "      - \"Deletes only files last modified more than 24 hours ago\"\n"
                  + "      - \"Deletes at most 12 GiB, oldest files first\"\n"
                  + "      - \"Runs only on the Subscriber DB server (lab-db-01)\"",
              "    limits: []",
              "registered_actions[0].limits: at least one limit"
            },
            new String[] {
              "      - \"Deletes at most 12 GiB, oldest files first\"",
              "      - \"\"",
              "registered_actions[0].limits[2]: 1 to 200 characters"
            },
            new String[] {
              "attempt_limit: 1 ",
              "attempt_limit: 0 ",
              "registered_actions[0].attempt_limit: a whole number from 1"
            },
            new String[] {
              PRECONDITION,
              "precondition: null",
              "registered_actions[0].precondition: not a mapping"
            },
            new String[] {
              "value: \"90\"}",
              "value: \"90\", extra: 1}",
              "registered_actions[0].precondition: unknown field extra"
            },
            new String[] {
              "{operation_key: fs_used_pct, parameters: {mount: /data}, result_field: value,"
                  + " operator: greater_or_equal",
              "{operation_key: \"fs used\", parameters: {mount: /data}, result_field: value,"
                  + " operator: greater_or_equal",
              "registered_actions[0].precondition.operation_key: not a key"
            },
            new String[] {
              "parameters: {mount: /data}, result_field: value, operator: greater_or_equal",
              "parameters: x, result_field: value, operator: greater_or_equal",
              "registered_actions[0].precondition.parameters: not a mapping"
            },
            new String[] {
              "result_field: value, operator: greater_or_equal",
              "result_field: \"\", operator: greater_or_equal",
              "registered_actions[0].precondition.result_field: 1 to 100 characters"
            },
            new String[] {
              "operator: less_than",
              "operator: below",
              "registered_actions[0].reconciliation.operator: one of equals, not_equals,"
                  + " greater_than, greater_or_equal, less_than, less_or_equal"
            },
            new String[] {
              "value: \"90\"}",
              "value: \"\"}",
              "registered_actions[0].precondition.value: 1 to 100 characters"
            },
            new String[] {
              "    recovery_check_key: rc-subscriber-db\n",
              "    recovery_check_key: \"rc x\"\n",
              "registered_actions[0].recovery_check_key: not a key"
            },
            new String[] {
              POLICY,
              "  - {action_key: other-action, service_key: null, outcome: approval_required}",
              "action_policies[0].action_key: not an action of this part"
            },
            new String[] {
              POLICY,
              POLICY
                  + "\n  - {action_key: purge-archived-txlogs, service_key: \"svc x\","
                  + " outcome: blocked}",
              "action_policies[1].service_key: not a key"
            },
            new String[] {
              "outcome: approval_required}",
              "outcome: maybe}",
              "action_policies[0].outcome: one of blocked, approval_required, preauthorized"
            });
    for (String[] c : cases) {
      assertRefused(changed(SeedTests.replacing(c[0], c[1])), c[2]);
    }

    SeedImportView whole =
        changed(
            t ->
                SeedTests.replacing("attempt_limit: 1 ", "attempt_limit: 0 ")
                    .andThen(SeedTests.replacing("outcome: approval_required}", "outcome: maybe}"))
                    .apply(t));
    assertRefused(
        whole,
        PREFIX
            + "registered_actions[0].attempt_limit: a whole number from 1; action_policies[0]"
            + ".outcome: one of blocked, approval_required, preauthorized");

    SeedImportView ok = demo();
    ActionsImport importer = app.context().getBean(ActionsImport.class);
    assertThatThrownBy(
            () ->
                importer.importPart(
                    ok.tenantId(), new ActionsPart(List.of(), List.of()), Instant.now(), Map.of()))
        .isInstanceOfSatisfying(
            VigilError.class,
            error -> {
              assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
              assertThat(error.message()).isEqualTo("Part actions refused.");
              assertThat(error.details().get("problems"))
                  .isEqualTo(List.of("registered actions already exist"));
            });
    assertThat(actions.registeredActions(ok.tenantId())).hasSize(1);
  }

  private void assertKeyCheck(SeedImportView view, int failed, String problem) {
    assertThat(view.state()).isEqualTo(SeedImportState.FAILED);
    assertThat(part(view, failed).outcome()).isEqualTo(PartOutcome.FAILED);
    assertThat(part(view, failed).error()).startsWith("Key check failed: ").contains(problem);
  }

  @Test
  @DisplayName("DM-R-60 part 14 against parts 4, 13 and 5; part 8 against part 14")
  void keyChecks() {
    assertKeyCheck(
        changed(
            SeedTests.replacing(
                "operation_key: purge_old_archives\n    parameter_values",
                "operation_key: purge_nowhere\n    parameter_values")),
        14,
        "registered action 'purge-archived-txlogs': operation 'purge_nowhere' does not exist");
    assertKeyCheck(
        changed(
            SeedTests.replacing(
                "operation_key: purge_old_archives\n    parameter_values",
                "operation_key: fs_used_pct\n    parameter_values")),
        14,
        "registered action 'purge-archived-txlogs': operation 'fs_used_pct' is not an action"
            + " operation");
    assertKeyCheck(
        changed(
            SeedTests.replacing(
                "{operation_key: fs_used_pct, parameters: {mount: /data}, result_field: value,"
                    + " operator: less_than",
                "{operation_key: purge_old_archives, parameters: {mount: /data}, result_field:"
                    + " value, operator: less_than")),
        14,
        "registered action 'purge-archived-txlogs': reconciliation operation 'purge_old_archives'"
            + " is not a diagnostic operation");
    assertKeyCheck(
        changed(
            SeedTests.replacing(
                "    recovery_check_key: rc-subscriber-db\n",
                "    recovery_check_key: rc-nowhere\n")),
        14,
        "registered action 'purge-archived-txlogs': recovery check 'rc-nowhere' does not exist");
    assertKeyCheck(
        changed(
            SeedTests.replacing(
                POLICY,
                POLICY
                    + "\n  - {action_key: purge-archived-txlogs, service_key: svc-nowhere,"
                    + " outcome: blocked}")),
        14,
        "action policy of 'purge-archived-txlogs': service 'svc-nowhere' does not exist");
    assertKeyCheck(
        imported(
            SeedTests.demoBundleWith(
                PAST, t -> t.replaceFirst("purge-archived-txlogs,4", "purge-nowhere,4"))),
        8,
        "past incident 'PI-1001': action 'purge-nowhere' does not exist");
  }

  @Test
  @DisplayName("AR-B-11 part 14 passes the handle map on unchanged, with its counts")
  void importInterface() {
    TenantId tenant =
        app.context().getBean(Installation.class).createTenant("actions " + UUID.randomUUID());
    Map<String, UUID> handles = new HashMap<>(Map.of("cred-x", UUID.randomUUID()));
    Map<String, UUID> before = Map.copyOf(handles);
    Map<String, JsonNode> files =
        Map.of(
            SeedParts.OPERATIONS,
            SeedYaml.read(SeedTests.text(SeedTests.demoEntries(), SeedParts.OPERATIONS)));
    PartResult result =
        app.context()
            .getBean(ActionsAdapter.class)
            .importPart(tenant, null, files, Instant.now(), handles);
    assertThat(result.counts()).isEqualTo(Map.of("registered_actions", 1, "action_policies", 1));
    assertThat(result.handles()).isEmpty();
    assertThat(handles).isEqualTo(before);
    assertThat(actions.registeredActions(tenant)).hasSize(1);
  }
}
