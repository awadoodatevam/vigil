package vigil.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vigil.setup.SeedParts.Part;

class SeedPartsTest {

  private static final Path DATA = Path.of("../docs/31-demo-data");

  /** One expected row of the catalogue of Section 6.4. */
  private record Row(int number, String name, String module, Map<String, List<String>> sections) {}

  private static Map<String, List<String>> of(String file, String... sections) {
    Map<String, List<String>> map = new LinkedHashMap<>();
    map.put(file, List.of(sections));
    return map;
  }

  private static final List<Row> EXPECTED =
      List.of(
          new Row(
              1,
              "tenant settings",
              "vigil-tenancy",
              of("01-tenant-identity.yaml", "tenant_settings")),
          new Row(
              2,
              "identity",
              "vigil-identity",
              of("01-tenant-identity.yaml", "teams", "users", "memberships", "permission_grants")),
          new Row(
              3,
              "connectors",
              "vigil-connectors",
              of("07-settings.yaml", "credential_references", "connector_instances")),
          new Row(
              4,
              "runner",
              "vigil-runner",
              of("04-operations-actions-recovery.yaml", "operations", "target_access_grants")),
          new Row(
              5,
              "service map",
              "vigil-servicemap",
              of("02-service-map.yaml", "services", "components", "identity_keys", "dependencies")),
          new Row(
              6,
              "intake",
              "vigil-intake",
              of("03-monitoring.yaml", "monitoring_sources", "alert_types", "alert_type_mappings")),
          new Row(
              7,
              "AI gateway",
              "vigil-ai-gateway",
              of(
                  "07-settings.yaml",
                  "ai_provider_configurations",
                  "allowed_providers",
                  "task_assignments",
                  "data_routing_rules")),
          new Row(8, "knowledge", "vigil-knowledge", knowledge()),
          new Row(9, "incidents", "vigil-incidents", of("07-settings.yaml", "incident_settings")),
          new Row(
              10,
              "correlation",
              "vigil-correlation",
              of("07-settings.yaml", "correlation_settings")),
          new Row(
              11,
              "routing",
              "vigil-routing",
              of(
                  "05-routing-escalation.yaml",
                  "routing_rule_set_revision_1",
                  "escalation_policy_revision_1")),
          new Row(
              12,
              "diagnostics",
              "vigil-diagnostics",
              of("04-operations-actions-recovery.yaml", "diagnostic_definitions")),
          new Row(
              13,
              "recovery",
              "vigil-recovery",
              of("04-operations-actions-recovery.yaml", "recovery_check_definitions")),
          new Row(
              14,
              "actions",
              "vigil-actions",
              of("04-operations-actions-recovery.yaml", "registered_actions", "action_policies")),
          new Row(
              15,
              "notifications",
              "vigil-notifications",
              of("07-settings.yaml", "delivery_addresses")),
          new Row(16, "workflows", "vigil-workflows", of("06-workflow.yaml", "workflow")),
          new Row(17, "reporting", "vigil-reporting", of("07-settings.yaml", "baseline_values")));

  private static Map<String, List<String>> knowledge() {
    Map<String, List<String>> map = new LinkedHashMap<>();
    map.put("07-settings.yaml", List.of("knowledge_settings"));
    map.put("08-runbooks.md", List.of());
    map.put("09-past-incidents.csv", List.of());
    return map;
  }

  @Test
  @DisplayName("DM-R-56 the part catalogue in DM-R-56 order matches the data files")
  void catalogue() throws IOException {
    assertThat(SeedParts.ALL).hasSize(17);
    for (Row row : EXPECTED) {
      Part part = SeedParts.ALL.get(row.number() - 1);
      assertThat(part.number()).isEqualTo(row.number());
      assertThat(part.name()).isEqualTo(row.name());
      assertThat(part.module()).isEqualTo(row.module());
      assertThat(new ArrayList<>(part.sections().keySet()))
          .isEqualTo(new ArrayList<>(row.sections().keySet()));
      assertThat(part.sections()).isEqualTo(row.sections());
    }
    assertThat(SeedParts.IGNORED)
        .isEqualTo(
            Map.of(
                "03-monitoring.yaml", List.of("lab_alert_catalogue"),
                "04-operations-actions-recovery.yaml", List.of("output_parsers"),
                "05-routing-escalation.yaml", List.of("routing_change_for_sl_a2_06"),
                "06-workflow.yaml", List.of("draft_changes_for_version_2")));

    for (String file : SeedParts.DATA_YAML) {
      JsonNode tree = SeedYaml.read(Files.readString(DATA.resolve(file), StandardCharsets.UTF_8));
      List<String> keys = new ArrayList<>(tree.propertyNames());
      for (String key : keys) {
        long owners =
            SeedParts.ALL.stream()
                .filter(p -> p.sections().getOrDefault(file, List.of()).contains(key))
                .count();
        boolean ignored = SeedParts.IGNORED.getOrDefault(file, List.of()).contains(key);
        assertThat(owners + (ignored ? 1 : 0))
            .as("%s: %s is a section of exactly one part or ignored", file, key)
            .isEqualTo(1);
      }
      for (Part part : SeedParts.ALL) {
        assertThat(keys).containsAll(part.sections().getOrDefault(file, List.of()));
      }
      assertThat(
              SeedParts.checkFile(
                  file, Files.readString(DATA.resolve(file)), new LinkedHashMap<>()))
          .as("file check of %s", file)
          .isEmpty();
    }

    Map<String, Integer> firstReaders = new LinkedHashMap<>();
    firstReaders.put("01-tenant-identity.yaml", 1);
    firstReaders.put("07-settings.yaml", 3);
    firstReaders.put("04-operations-actions-recovery.yaml", 4);
    firstReaders.put("02-service-map.yaml", 5);
    firstReaders.put("03-monitoring.yaml", 6);
    firstReaders.put("05-routing-escalation.yaml", 11);
    firstReaders.put("06-workflow.yaml", 16);
    assertThat(SeedParts.FIRST_READER).isEqualTo(firstReaders);
    assertThat(SeedParts.filesFirstReadBy(3)).containsExactly("07-settings.yaml");
    assertThat(SeedParts.filesFirstReadBy(2)).isEmpty();
  }
}
