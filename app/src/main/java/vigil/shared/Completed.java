package vigil.shared;

import java.util.Objects;
import java.util.UUID;

/** The step finished with a named output; {@code resultRef} may be {@code null} (AR-R-21). */
public record Completed(Key namedOutput, ResultRef resultRef) implements StepAnswer {

  /** The record holding the step's result (DM-E-51 {@code result_ref}). */
  public record ResultRef(String module, String entity, UUID id) {

    public ResultRef {
      Objects.requireNonNull(module, "module");
      Objects.requireNonNull(entity, "entity");
      Objects.requireNonNull(id, "id");
    }
  }

  public Completed {
    Objects.requireNonNull(namedOutput, "namedOutput");
  }
}
