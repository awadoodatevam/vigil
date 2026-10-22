package vigil.incidents.api;

import java.util.Arrays;
import java.util.Optional;

/** The kinds of evidence entry (DM-E-38, complete list). */
public enum EvidenceKind {
  DIAGNOSTIC_RESULT("diagnostic_result"),
  AI_INVESTIGATION("ai_investigation"),
  SIMILARITY_MATCH("similarity_match"),
  APPROVAL("approval"),
  ACTION_EXECUTION("action_execution"),
  RECOVERY_VERIFICATION("recovery_verification");

  private final String key;

  EvidenceKind(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<EvidenceKind> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
