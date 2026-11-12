package vigil.actions;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.actions.api.StateCheck;
import vigil.actions.api.StateCheckOperator;
import vigil.runner.api.OperationRunState;
import vigil.runner.api.OperationRunView;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunRequest;
import vigil.runner.api.RunnerService;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.shared.TransactionGuard;

/**
 * State checks (DM-V-08; P-24 spec Section 6.5): the operation run with diagnostic permission, the
 * value of its result field, and the comparison, as numbers when both sides are decimals.
 */
@Component
class StateChecks {

  static final Actor ACTIONS = Actor.module("vigil-actions");

  /** A completed state check: its run, the value read ({@code null} for none) and the result. */
  record Outcome(UUID runId, String value, Boolean result) {}

  private final RunnerService runner;

  StateChecks(RunnerService runner) {
    this.runner = runner;
  }

  /** Runs the check on the target with the request id; no transaction may be open. */
  Outcome check(TenantId tenantId, String requestId, StateCheck check, String targetKey) {
    TransactionGuard.assertNoTransaction();
    OperationRunView run =
        runner.run(
            tenantId,
            ACTIONS,
            new RunRequest(
                requestId,
                PermissionKind.DIAGNOSTIC,
                check.operationKey(),
                targetKey,
                check.parametersJson()));
    return outcome(run, check);
  }

  /** The outcome of a check from its run. */
  static Outcome outcome(OperationRunView run, StateCheck check) {
    String value = value(run, check.resultField());
    return new Outcome(
        run.id(), value, value == null ? null : compare(value, check.operator(), check.value()));
  }

  /**
   * The value named {@code resultField} of a {@code succeeded} run as text; {@code null} for any
   * other run, a missing entry or a {@code null} value.
   */
  static String value(OperationRunView run, String resultField) {
    if (run == null
        || run.state() != OperationRunState.SUCCEEDED
        || run.outputValuesJson() == null) {
      return null;
    }
    JsonNode values = ActionRecords.JSON.readTree(run.outputValuesJson());
    for (JsonNode entry : values) {
      JsonNode name = entry.get("name");
      if (name != null && resultField.equals(name.asString())) {
        JsonNode value = entry.get("value");
        return value == null || value.isNull() ? null : value.asString();
      }
    }
    return null;
  }

  /** The comparison of CTX-D-075 (5): as numbers when both parse as decimals, else as texts. */
  static boolean compare(String value, StateCheckOperator operator, String expected) {
    BigDecimal left = decimal(value);
    BigDecimal right = decimal(expected);
    int order =
        left != null && right != null ? left.compareTo(right) : codePointOrder(value, expected);
    return switch (operator) {
      case EQUALS -> order == 0;
      case NOT_EQUALS -> order != 0;
      case GREATER_THAN -> order > 0;
      case GREATER_OR_EQUAL -> order >= 0;
      case LESS_THAN -> order < 0;
      case LESS_OR_EQUAL -> order <= 0;
    };
  }

  static int codePointOrder(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      int x = a.codePointAt(i);
      int y = b.codePointAt(j);
      if (x != y) {
        return Integer.compare(x, y);
      }
      i += Character.charCount(x);
      j += Character.charCount(y);
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  private static BigDecimal decimal(String text) {
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // The JSON form: {operation_key, parameters, result_field, operator, value}.

  static ObjectNode json(StateCheck check) {
    ObjectNode node = ActionRecords.JSON.createObjectNode();
    node.put("operation_key", check.operationKey());
    node.set("parameters", ActionRecords.JSON.readTree(check.parametersJson()));
    node.put("result_field", check.resultField());
    node.put("operator", check.operator().key());
    node.put("value", check.value());
    return node;
  }

  static String write(StateCheck check) {
    return ActionRecords.JSON.writeValueAsString(json(check));
  }

  static StateCheck read(String json) {
    JsonNode node = ActionRecords.JSON.readTree(json);
    return new StateCheck(
        node.get("operation_key").stringValue(),
        ActionRecords.JSON.writeValueAsString(node.get("parameters")),
        node.get("result_field").stringValue(),
        StateCheckOperator.fromKey(node.get("operator").stringValue()).orElseThrow(),
        node.get("value").stringValue());
  }
}
