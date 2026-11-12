package vigil.incidents.api;

import java.util.Map;

/**
 * The refusal that an acknowledgement would give (DM-E35-R1, DC-S04-05): its key, its message and
 * the details of the {@code REFUSED} error (with {@code refusal} the key).
 */
public record AcknowledgeRefusal(String key, String message, Map<String, String> details) {

  public AcknowledgeRefusal {
    details = Map.copyOf(details);
  }
}
