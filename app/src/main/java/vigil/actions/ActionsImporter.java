package vigil.actions;

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
import vigil.actions.api.ActionPolicyView;
import vigil.actions.api.ActionsImport;
import vigil.actions.api.ActionsPart;
import vigil.actions.api.ActionsPart.ActionPart;
import vigil.actions.api.ActionsPart.PolicyPart;
import vigil.actions.api.PolicyOutcome;
import vigil.actions.api.RegisteredActionView;
import vigil.actions.api.StateCheck;
import vigil.actions.api.StateCheckOperator;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/**
 * The seed import of part 14: the registered actions and action policies (DM-E-55, DM-E-56,
 * DD-F-04), checked as a whole (DM-R-57; P-24 spec Section 6.11). Operations, recovery checks and
 * services are checked by the key checks of part 14. It writes no audit event.
 */
@Service
class ActionsImporter implements ActionsImport {

  static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final Pattern WHOLE = Pattern.compile("^[0-9]{1,18}$");
  private static final Set<String> CHECK_FIELDS =
      Set.of("operation_key", "parameters", "result_field", "operator", "value");
  private static final String ACTIONS = "registered_actions";
  private static final String POLICIES = "action_policies";
  private static final Actor SETUP = Actor.module("vigil-setup");

  private final ActionRecords records;
  private final TransactionTemplate tx;
  private final Clock clock;

  ActionsImporter(
      ActionRecords records, PlatformTransactionManager transactionManager, Clock clock) {
    this.records = records;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public Map<String, UUID> importPart(
      TenantId tenantId, ActionsPart part, Instant importStartedAt, Map<String, UUID> handles) {
    Actions.require(tenantId, part, importStartedAt, handles);
    Actions.require(part.actions(), part.policies());
    Actions.require(part.actions().toArray());
    Actions.require(part.policies().toArray());
    return tx.execute(
        status -> {
          List<String> problems = problems(tenantId, part);
          if (!problems.isEmpty()) {
            throw new VigilError(
                VigilError.Code.INVALID, "Part actions refused.", Map.of("problems", problems));
          }
          Instant now = Timestamps.now(clock);
          for (ActionPart action : part.actions()) {
            List<String> limits = new ArrayList<>();
            action.limits().forEach(limit -> limits.add(limit.asString()));
            records.insertAction(
                tenantId,
                new RegisteredActionView(
                    action.actionKey(),
                    action.name(),
                    action.operationKey(),
                    ActionRecords.JSON.writeValueAsString(action.parameterValues()),
                    limits,
                    Long.parseLong(action.attemptLimit()),
                    stateCheck(action.precondition()),
                    stateCheck(action.reconciliation()),
                    action.recoveryCheckKey(),
                    true,
                    SETUP),
                now);
          }
          for (PolicyPart policy : part.policies()) {
            records.insertPolicy(
                tenantId,
                new ActionPolicyView(
                    policy.actionKey(),
                    policy.serviceKey(),
                    PolicyOutcome.fromKey(policy.outcome()).orElseThrow(),
                    true),
                now);
          }
          return Map.of();
        });
  }

  private List<String> problems(TenantId tenantId, ActionsPart part) {
    if (records.hasActions(tenantId)) {
      return List.of("registered actions already exist");
    }
    List<String> problems = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < part.actions().size(); i++) {
      String at = ACTIONS + "[" + i + "]";
      ActionPart action = part.actions().get(i);
      if (!isKey(action.actionKey())) {
        problems.add(at + ".action_key: not a key");
      } else if (!keys.add(action.actionKey())) {
        problems.add(at + ".action_key: used more than once");
      }
      if (!lengthBetween(action.name(), 100)) {
        problems.add(at + ".name: 1 to 100 characters");
      }
      if (!isKey(action.operationKey())) {
        problems.add(at + ".operation_key: not a key");
      }
      JsonNode values = present(action.parameterValues());
      if (values == null || !values.isObject()) {
        problems.add(at + ".parameter_values: not a mapping");
      }
      JsonNode limits = present(action.limits());
      if (limits == null || !limits.isArray() || limits.isEmpty()) {
        problems.add(at + ".limits: at least one limit");
      } else {
        for (int k = 0; k < limits.size(); k++) {
          JsonNode limit = limits.get(k);
          if (!isScalar(limit) || !lengthBetween(limit.asString(), 200)) {
            problems.add(at + ".limits[" + k + "]: 1 to 200 characters");
          }
        }
      }
      String attemptLimit = action.attemptLimit();
      if (attemptLimit == null
          || !WHOLE.matcher(attemptLimit).matches()
          || Long.parseLong(attemptLimit) < 1) {
        problems.add(at + ".attempt_limit: a whole number from 1");
      }
      checkProblems(action.precondition(), at + ".precondition", problems);
      checkProblems(action.reconciliation(), at + ".reconciliation", problems);
      if (!isKey(action.recoveryCheckKey())) {
        problems.add(at + ".recovery_check_key: not a key");
      }
    }
    Set<String> pairs = new HashSet<>();
    Set<String> withDefault = new HashSet<>();
    for (int j = 0; j < part.policies().size(); j++) {
      String at = POLICIES + "[" + j + "]";
      PolicyPart policy = part.policies().get(j);
      if (policy.actionKey() == null || !keys.contains(policy.actionKey())) {
        problems.add(at + ".action_key: not an action of this part");
      }
      if (policy.serviceKey() != null && !isKey(policy.serviceKey())) {
        problems.add(at + ".service_key: not a key");
      }
      if (PolicyOutcome.fromKey(policy.outcome()).isEmpty()) {
        problems.add(at + ".outcome: one of blocked, approval_required, preauthorized");
      }
      String service = policy.serviceKey() == null ? "every service" : policy.serviceKey();
      if (!pairs.add(policy.actionKey() + "\n" + service)) {
        problems.add(at + ": a second policy for " + policy.actionKey() + " and " + service);
      }
      if (policy.serviceKey() == null) {
        withDefault.add(policy.actionKey());
      }
    }
    for (int i = 0; i < part.actions().size(); i++) {
      String key = part.actions().get(i).actionKey();
      if (isKey(key) && !withDefault.contains(key)) {
        problems.add(ACTIONS + "[" + i + "]: exactly one policy for every service");
      }
    }
    return problems;
  }

  /** The problems of a state check (DM-V-08). */
  private static void checkProblems(JsonNode node, String at, List<String> problems) {
    JsonNode check = present(node);
    if (check == null || !check.isObject()) {
      problems.add(at + ": not a mapping");
      return;
    }
    for (String name : check.propertyNames()) {
      if (!CHECK_FIELDS.contains(name)) {
        problems.add(at + ": unknown field " + name);
      }
    }
    JsonNode operation = present(check.get("operation_key"));
    if (operation == null || !operation.isString() || !isKey(operation.stringValue())) {
      problems.add(at + ".operation_key: not a key");
    }
    JsonNode parameters = present(check.get("parameters"));
    if (parameters == null || !parameters.isObject()) {
      problems.add(at + ".parameters: not a mapping");
    }
    JsonNode field = present(check.get("result_field"));
    if (!isScalar(field) || !lengthBetween(field.asString(), 100)) {
      problems.add(at + ".result_field: 1 to 100 characters");
    }
    JsonNode operator = present(check.get("operator"));
    if (operator == null
        || !operator.isString()
        || StateCheckOperator.fromKey(operator.stringValue()).isEmpty()) {
      problems.add(
          at
              + ".operator: one of equals, not_equals, greater_than, greater_or_equal, less_than,"
              + " less_or_equal");
    }
    JsonNode value = present(check.get("value"));
    if (!isScalar(value) || !lengthBetween(value.asString(), 100)) {
      problems.add(at + ".value: 1 to 100 characters");
    }
  }

  /** The state check of a checked part. */
  private static StateCheck stateCheck(JsonNode check) {
    return new StateCheck(
        check.get("operation_key").stringValue(),
        ActionRecords.JSON.writeValueAsString(check.get("parameters")),
        check.get("result_field").asString(),
        StateCheckOperator.fromKey(check.get("operator").stringValue()).orElseThrow(),
        check.get("value").asString());
  }

  /** The value; {@code null} when absent or YAML {@code null}. */
  private static JsonNode present(JsonNode value) {
    return value == null || value.isNull() || value.isMissingNode() ? null : value;
  }

  private static boolean isScalar(JsonNode value) {
    return value != null && value.isValueNode() && !value.isNull();
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
