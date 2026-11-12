package vigil.actions.api;

import java.util.List;
import vigil.shared.Actor;

/** A registered action (DM-E-55); {@code parameterValuesJson} is the stored JSON object. */
public record RegisteredActionView(
    String actionKey,
    String name,
    String operationKey,
    String parameterValuesJson,
    List<String> limits,
    long attemptLimit,
    StateCheck precondition,
    StateCheck reconciliation,
    String recoveryCheckKey,
    boolean active,
    Actor updatedBy) {

  public RegisteredActionView {
    limits = List.copyOf(limits);
  }
}
