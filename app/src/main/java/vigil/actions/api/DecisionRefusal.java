package vigil.actions.api;

import java.util.Map;

/**
 * The refusal that a decision on an approval request would give (DM-E59-R1, DC-S05-06): its key,
 * its message and the details of the {@code REFUSED} error (with {@code refusal} the key).
 */
public record DecisionRefusal(String key, String message, Map<String, String> details) {

  public DecisionRefusal {
    details = Map.copyOf(details);
  }
}
