package vigil.incidents.api;

import java.util.Arrays;
import java.util.Optional;

/** The causes of an escalation (DM-E-36 {@code cause}, complete list). */
public enum EscalationCause {
  ACKNOWLEDGEMENT_TIMEOUT("acknowledgement_timeout"),
  ESCALATE_NOW("escalate_now");

  private final String key;

  EscalationCause(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<EscalationCause> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
