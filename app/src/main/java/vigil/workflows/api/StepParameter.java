package vigil.workflows.api;

import java.util.List;

/**
 * A parameter of a step type (DM Section 9). {@code requiredWhen}, {@code min} and {@code max} are
 * {@code null} when not used; {@code allowedValues} is empty when not used; {@code minItems} is 0
 * when not used. A {@code DURATION} is in seconds.
 */
public record StepParameter(
    String name,
    StepParameterType type,
    boolean required,
    RequiredWhen requiredWhen,
    List<String> allowedValues,
    Long min,
    Long max,
    int minItems) {

  public StepParameter {
    allowedValues = List.copyOf(allowedValues);
  }
}
