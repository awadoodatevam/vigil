package vigil.actions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.actions.api.StateCheck;
import vigil.actions.api.StateCheckOperator;
import vigil.runner.api.OperationRunState;
import vigil.runner.api.OperationRunView;
import vigil.runner.api.PermissionKind;

class StateChecksTest {

  private static OperationRunView run(OperationRunState state, String values) {
    return new OperationRunView(
        UUID.randomUUID(),
        "fs_used_pct",
        "lab-db-01",
        PermissionKind.DIAGNOSTIC,
        "{}",
        "vigil-actions",
        "r",
        state,
        Instant.EPOCH,
        Instant.EPOCH,
        0L,
        values,
        null,
        false,
        null);
  }

  @Test
  @DisplayName("DM-V-08 numbers compared as numbers, texts by code point, no value otherwise")
  void stateChecks() {
    assertThat(StateChecks.compare("100", StateCheckOperator.GREATER_OR_EQUAL, "90")).isTrue();
    assertThat(StateChecks.compare("9", StateCheckOperator.LESS_THAN, "10")).isTrue();
    assertThat(StateChecks.compare("9", StateCheckOperator.GREATER_THAN, "10")).isFalse();
    assertThat(StateChecks.compare("1.0", StateCheckOperator.EQUALS, "1")).isTrue();
    assertThat(StateChecks.compare("1.0", StateCheckOperator.NOT_EQUALS, "1")).isFalse();
    assertThat(StateChecks.compare("90", StateCheckOperator.LESS_OR_EQUAL, "90")).isTrue();
    assertThat(StateChecks.compare("9", StateCheckOperator.GREATER_THAN, "10x")).isTrue();
    assertThat(StateChecks.compare("abc", StateCheckOperator.LESS_THAN, "abd")).isTrue();
    assertThat(StateChecks.compare("abc", StateCheckOperator.EQUALS, "abc")).isTrue();
    assertThat(StateChecks.compare("abc", StateCheckOperator.NOT_EQUALS, "abC")).isTrue();
    assertThat(StateChecks.compare("Z", StateCheckOperator.LESS_THAN, "a")).isTrue();
    assertThat(StateChecks.compare("ab", StateCheckOperator.LESS_OR_EQUAL, "abc")).isTrue();
    assertThat(StateChecks.compare("b", StateCheckOperator.GREATER_OR_EQUAL, "abc")).isTrue();

    String values = "[{\"name\":\"value\",\"value\":100},{\"name\":\"other\",\"value\":null}]";
    StateCheck check =
        new StateCheck(
            "fs_used_pct",
            "{\"mount\":\"/data\"}",
            "value",
            StateCheckOperator.GREATER_OR_EQUAL,
            "90");
    assertThat(StateChecks.value(run(OperationRunState.SUCCEEDED, values), "value"))
        .isEqualTo("100");
    assertThat(StateChecks.outcome(run(OperationRunState.SUCCEEDED, values), check).result())
        .isTrue();
    assertThat(StateChecks.value(run(OperationRunState.FAILED, values), "value")).isNull();
    assertThat(StateChecks.value(run(OperationRunState.UNKNOWN, values), "value")).isNull();
    assertThat(StateChecks.value(run(OperationRunState.RUNNING, values), "value")).isNull();
    assertThat(StateChecks.value(run(OperationRunState.SUCCEEDED, values), "missing")).isNull();
    assertThat(StateChecks.value(run(OperationRunState.SUCCEEDED, values), "other")).isNull();
    assertThat(StateChecks.value(run(OperationRunState.SUCCEEDED, null), "value")).isNull();
    StateChecks.Outcome none = StateChecks.outcome(run(OperationRunState.RUNNING, values), check);
    assertThat(none.value()).isNull();
    assertThat(none.result()).isNull();
  }
}
