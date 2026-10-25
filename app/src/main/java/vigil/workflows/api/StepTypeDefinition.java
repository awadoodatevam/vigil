package vigil.workflows.api;

import java.util.List;
import vigil.shared.StepCall.StepType;

/** A step type of the catalogue (DM-E-44): its parameters and named outputs, in order. */
public record StepTypeDefinition(
    StepType type, List<StepParameter> parameters, List<StepOutput> outputs) {

  public StepTypeDefinition {
    parameters = List.copyOf(parameters);
    outputs = List.copyOf(outputs);
  }
}
