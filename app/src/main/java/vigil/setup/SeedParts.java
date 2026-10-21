package vigil.setup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The seed bundle's entries (AR-B-05) and its 17 parts in the order of DM-R-56, with the entries
 * and top-level sections each part reads, and the file checks of AR-B-05 (BP-A-23).
 */
final class SeedParts {

  static final String BUNDLE = "bundle.yaml";
  static final String TENANT_IDENTITY = "01-tenant-identity.yaml";
  static final String SERVICE_MAP = "02-service-map.yaml";
  static final String MONITORING = "03-monitoring.yaml";
  static final String OPERATIONS = "04-operations-actions-recovery.yaml";
  static final String ROUTING = "05-routing-escalation.yaml";
  static final String WORKFLOW = "06-workflow.yaml";
  static final String SETTINGS = "07-settings.yaml";
  static final String RUNBOOKS = "08-runbooks.md";
  static final String PAST_INCIDENTS = "09-past-incidents.csv";
  static final String LOGO_PNG = "logo.png";
  static final String LOGO_SVG = "logo.svg";

  /** The ten required entries, in the order of AR-B-05; all of them are text entries. */
  static final List<String> REQUIRED =
      List.of(
          BUNDLE,
          TENANT_IDENTITY,
          SERVICE_MAP,
          MONITORING,
          OPERATIONS,
          ROUTING,
          WORKFLOW,
          SETTINGS,
          RUNBOOKS,
          PAST_INCIDENTS);

  /** Every allowed entry, in the order of AR-B-05. */
  static final List<String> ALLOWED = allowed();

  /** The data files read as YAML during the import. */
  static final List<String> DATA_YAML =
      List.of(TENANT_IDENTITY, SERVICE_MAP, MONITORING, OPERATIONS, ROUTING, WORKFLOW, SETTINGS);

  /** One part: its number, the name of its refusal message, its module and what it reads. */
  record Part(int number, String name, String module, Map<String, List<String>> sections) {

    Part {
      sections = Collections.unmodifiableMap(new LinkedHashMap<>(sections));
    }
  }

  /** The 17 parts in the order of DM-R-56 (complete list); a file without sections is whole. */
  static final List<Part> ALL =
      List.of(
          part(1, "tenant settings", "vigil-tenancy", TENANT_IDENTITY, List.of("tenant_settings")),
          part(
              2,
              "identity",
              "vigil-identity",
              TENANT_IDENTITY,
              List.of("teams", "users", "memberships", "permission_grants")),
          part(
              3,
              "connectors",
              "vigil-connectors",
              SETTINGS,
              List.of("credential_references", "connector_instances")),
          part(
              4,
              "runner",
              "vigil-runner",
              OPERATIONS,
              List.of("operations", "target_access_grants")),
          part(
              5,
              "service map",
              "vigil-servicemap",
              SERVICE_MAP,
              List.of("services", "components", "identity_keys", "dependencies")),
          part(
              6,
              "intake",
              "vigil-intake",
              MONITORING,
              List.of("monitoring_sources", "alert_types", "alert_type_mappings")),
          part(
              7,
              "AI gateway",
              "vigil-ai-gateway",
              SETTINGS,
              List.of(
                  "ai_provider_configurations",
                  "allowed_providers",
                  "task_assignments",
                  "data_routing_rules")),
          new Part(
              8,
              "knowledge",
              "vigil-knowledge",
              orderedMap(
                  SETTINGS,
                  List.of("knowledge_settings"),
                  RUNBOOKS,
                  List.of(),
                  PAST_INCIDENTS,
                  List.of())),
          part(9, "incidents", "vigil-incidents", SETTINGS, List.of("incident_settings")),
          part(10, "correlation", "vigil-correlation", SETTINGS, List.of("correlation_settings")),
          part(
              11,
              "routing",
              "vigil-routing",
              ROUTING,
              List.of("routing_rule_set_revision_1", "escalation_policy_revision_1")),
          part(
              12,
              "diagnostics",
              "vigil-diagnostics",
              OPERATIONS,
              List.of("diagnostic_definitions")),
          part(13, "recovery", "vigil-recovery", OPERATIONS, List.of("recovery_check_definitions")),
          part(
              14,
              "actions",
              "vigil-actions",
              OPERATIONS,
              List.of("registered_actions", "action_policies")),
          part(15, "notifications", "vigil-notifications", SETTINGS, List.of("delivery_addresses")),
          part(16, "workflows", "vigil-workflows", WORKFLOW, List.of("workflow")),
          part(17, "reporting", "vigil-reporting", SETTINGS, List.of("baseline_values")));

  /** The sections of AR-B-05 that are not seed data (complete list), by file. */
  static final Map<String, List<String>> IGNORED =
      orderedMap(
          MONITORING,
          List.of("lab_alert_catalogue"),
          OPERATIONS,
          List.of("output_parsers"),
          ROUTING,
          List.of("routing_change_for_sl_a2_06"),
          WORKFLOW,
          List.of("draft_changes_for_version_2"));

  /** Each data YAML file and the number of the first part in DM-R-56 order that reads it. */
  static final Map<String, Integer> FIRST_READER = firstReaders();

  private SeedParts() {}

  /** The part numbered {@code number}. */
  static Part part(int number) {
    if (number < 1 || number > ALL.size()) {
      throw new IllegalArgumentException("no part " + number + " in the catalogue");
    }
    return ALL.get(number - 1);
  }

  /** The data YAML files whose check is at the turn of part {@code number}, in entry order. */
  static List<String> filesFirstReadBy(int number) {
    List<String> files = new ArrayList<>();
    FIRST_READER.forEach(
        (file, reader) -> {
          if (reader == number) {
            files.add(file);
          }
        });
    return files;
  }

  /**
   * The problems of the file check of {@code file} (complete list of AR-B-05 with BP-A-23); puts
   * its tree into {@code trees} when it can be read.
   */
  static List<String> checkFile(String file, String text, Map<String, JsonNode> trees) {
    JsonNode tree;
    try {
      tree = SeedYaml.read(text);
    } catch (JacksonException e) {
      return List.of(file + " could not be read: " + SeedYaml.parserMessage(e));
    }
    if (tree == null || !tree.isObject()) {
      return List.of(file + ": not a mapping");
    }
    trees.put(file, tree);
    Set<String> known = new LinkedHashSet<>();
    ALL.forEach(part -> known.addAll(part.sections().getOrDefault(file, List.of())));
    known.addAll(IGNORED.getOrDefault(file, List.of()));
    List<String> problems = new ArrayList<>();
    for (String key : tree.propertyNames()) {
      if (!known.contains(key)) {
        problems.add(file + ": unknown section " + key);
      }
    }
    return problems;
  }

  private static Part part(
      int number, String name, String module, String file, List<String> sections) {
    return new Part(number, name, module, Map.of(file, sections));
  }

  private static Map<String, List<String>> orderedMap(Object... pairs) {
    Map<String, List<String>> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      @SuppressWarnings("unchecked")
      List<String> value = (List<String>) pairs[i + 1];
      map.put((String) pairs[i], value);
    }
    return Collections.unmodifiableMap(map);
  }

  private static List<String> allowed() {
    List<String> allowed = new ArrayList<>(REQUIRED);
    allowed.add(LOGO_PNG);
    allowed.add(LOGO_SVG);
    return List.copyOf(allowed);
  }

  private static Map<String, Integer> firstReaders() {
    Map<String, Integer> readers = new LinkedHashMap<>();
    for (String file : DATA_YAML) {
      for (Part part : ALL) {
        if (part.sections().containsKey(file)) {
          readers.put(file, part.number());
          break;
        }
      }
    }
    return Collections.unmodifiableMap(readers);
  }
}
