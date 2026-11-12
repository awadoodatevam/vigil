package vigil.recovery.api;

import java.time.Duration;
import java.util.List;

/**
 * A recovery check definition (DM-E-61); {@code requirementTexts} one per condition, in order, in
 * the requirement form of DC-TX-03 (PM-M15-04).
 */
public record RecoveryCheckView(
    String recoveryCheckKey,
    String name,
    List<RecoveryCondition> conditions,
    Duration observationWindow,
    boolean isDefault,
    boolean active,
    List<String> requirementTexts) {

  public RecoveryCheckView {
    conditions = List.copyOf(conditions);
    requirementTexts = List.copyOf(requirementTexts);
  }
}
