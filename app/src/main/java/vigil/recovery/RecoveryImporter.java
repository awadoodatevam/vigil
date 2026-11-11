package vigil.recovery;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vigil.recovery.RecoveryRecords.Definition;
import vigil.recovery.api.ConditionKind;
import vigil.recovery.api.RecoveryCondition;
import vigil.recovery.api.RecoveryImport;
import vigil.recovery.api.RecoveryPart;
import vigil.recovery.api.RecoveryPart.DefinitionPart;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/**
 * The seed import of part 13: the recovery check definitions (DM-E-61, DD-F-04), checked as a whole
 * (DM-R-57; P-23 spec Section 6.8). The components and alert types of the conditions are checked by
 * the key checks of part 13 against parts 5 and 6. It writes no audit event.
 */
@Service
class RecoveryImporter implements RecoveryImport {

  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final Pattern WHOLE = Pattern.compile("^[0-9]{1,9}$");
  private static final int NAME_MAX = 100;
  private static final long WINDOW_MAX = 86_400;
  private static final Set<String> FIELDS = Set.of("kind", "component_key", "alert_types");
  private static final String SECTION = "recovery_check_definitions";

  private final RecoveryRecords records;
  private final TransactionTemplate tx;
  private final Clock clock;

  RecoveryImporter(
      RecoveryRecords records, PlatformTransactionManager transactionManager, Clock clock) {
    this.records = records;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public Map<String, UUID> importPart(
      TenantId tenantId, RecoveryPart part, Instant importStartedAt, Map<String, UUID> handles) {
    Recovery.require(tenantId, part, importStartedAt, handles);
    Recovery.require(part.definitions());
    Recovery.require(part.definitions().toArray());
    return tx.execute(
        status -> {
          List<String> problems = problems(tenantId, part);
          if (!problems.isEmpty()) {
            throw new VigilError(
                VigilError.Code.INVALID, "Part recovery refused.", Map.of("problems", problems));
          }
          Instant now = Timestamps.now(clock);
          for (DefinitionPart definition : part.definitions()) {
            records.insertDefinition(
                tenantId,
                new Definition(
                    definition.recoveryCheckKey(),
                    definition.name(),
                    conditions(definition.conditions()),
                    Long.parseLong(definition.observationWindowS()),
                    Boolean.parseBoolean(definition.isDefault()),
                    true),
                now);
          }
          return Map.of();
        });
  }

  private List<String> problems(TenantId tenantId, RecoveryPart part) {
    if (records.hasDefinitions(tenantId)) {
      return List.of("recovery check definitions already exist");
    }
    List<String> problems = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    int defaults = 0;
    for (int i = 0; i < part.definitions().size(); i++) {
      String at = SECTION + "[" + i + "]";
      DefinitionPart definition = part.definitions().get(i);
      if (!isKey(definition.recoveryCheckKey())) {
        problems.add(at + ".recovery_check_key: not a key");
      } else if (!keys.add(definition.recoveryCheckKey())) {
        problems.add(at + ".recovery_check_key: used more than once");
      }
      if (!lengthBetween(definition.name(), NAME_MAX)) {
        problems.add(at + ".name: 1 to 100 characters");
      }
      JsonNode conditions = definition.conditions();
      if (conditions == null
          || conditions.isNull()
          || !conditions.isArray()
          || conditions.isEmpty()) {
        problems.add(at + ".conditions: at least one condition");
      } else {
        for (int j = 0; j < conditions.size(); j++) {
          conditionProblems(conditions.get(j), at + ".conditions[" + j + "]", problems);
        }
      }
      String window = definition.observationWindowS();
      if (window == null
          || !WHOLE.matcher(window).matches()
          || Long.parseLong(window) < 1
          || Long.parseLong(window) > WINDOW_MAX) {
        problems.add(at + ".observation_window_s: 1 to 86400 seconds");
      }
      String isDefault = definition.isDefault();
      if (!"true".equals(isDefault) && !"false".equals(isDefault)) {
        problems.add(at + ".is_default: true or false");
      } else if ("true".equals(isDefault)) {
        defaults++;
      }
    }
    if (defaults != 1) {
      problems.add(SECTION + ": exactly one default");
    }
    return problems;
  }

  private static void conditionProblems(JsonNode condition, String at, List<String> problems) {
    if (!condition.isObject()) {
      problems.add(at + ": not a mapping");
      return;
    }
    for (String name : condition.propertyNames()) {
      if (!FIELDS.contains(name)) {
        problems.add(at + ": unknown field " + name);
      }
    }
    JsonNode kindNode = present(condition, "kind");
    ConditionKind kind =
        kindNode == null || !kindNode.isString()
            ? null
            : ConditionKind.fromKey(kindNode.stringValue()).orElse(null);
    if (kind == null) {
      problems.add(at + ".kind: one of incident_alerts_resolved, component_no_firing_alert");
      return;
    }
    JsonNode component = present(condition, "component_key");
    JsonNode types = present(condition, "alert_types");
    if (kind == ConditionKind.INCIDENT_ALERTS_RESOLVED) {
      if (component != null) {
        problems.add(at + ".component_key: not allowed for incident_alerts_resolved");
      }
      if (types != null && !(types.isArray() && types.isEmpty())) {
        problems.add(at + ".alert_types: not allowed for incident_alerts_resolved");
      }
      return;
    }
    if (component == null || !component.isString() || !isKey(component.stringValue())) {
      problems.add(at + ".component_key: not a key");
    }
    if (types == null) {
      return;
    }
    if (!types.isArray()) {
      problems.add(at + ".alert_types: not a list");
      return;
    }
    Set<String> seen = new HashSet<>();
    for (int k = 0; k < types.size(); k++) {
      JsonNode type = types.get(k);
      if (!type.isString() || !isKey(type.stringValue())) {
        problems.add(at + ".alert_types[" + k + "]: not a key");
      } else if (!seen.add(type.stringValue())) {
        problems.add(at + ".alert_types[" + k + "]: used more than once");
      }
    }
  }

  /** The conditions of a checked part. */
  private static List<RecoveryCondition> conditions(JsonNode array) {
    List<RecoveryCondition> conditions = new ArrayList<>();
    for (JsonNode condition : array) {
      ConditionKind kind = ConditionKind.fromKey(condition.get("kind").stringValue()).orElseThrow();
      if (kind == ConditionKind.INCIDENT_ALERTS_RESOLVED) {
        conditions.add(new RecoveryCondition(kind, null, List.of()));
        continue;
      }
      List<String> types = new ArrayList<>();
      JsonNode alertTypes = present(condition, "alert_types");
      if (alertTypes != null) {
        alertTypes.forEach(type -> types.add(type.stringValue()));
      }
      conditions.add(
          new RecoveryCondition(kind, condition.get("component_key").stringValue(), types));
    }
    return conditions;
  }

  /** The field's value; {@code null} when absent or YAML {@code null}. */
  private static JsonNode present(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value;
  }

  private static boolean isKey(String value) {
    return value != null && KEY.matcher(value).matches();
  }

  private static boolean lengthBetween(String value, int max) {
    if (value == null) {
      return false;
    }
    int length = value.codePointCount(0, value.length());
    return length >= 1 && length <= max;
  }
}
