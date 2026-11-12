package vigil.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-28: the recovery verifications of the latest live run (DC-S03-11). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record RecoverySection(List<Verification> verifications) {

  /** A recovery verification (DM-E-62) with its last evaluation. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Verification(
      String recoveryVerificationId,
      String checkName,
      String state,
      long observationWindowSeconds,
      String startedAt,
      String maxWaitUntil,
      String windowStartedAt,
      String decidedAt,
      List<Condition> conditions,
      Boolean sourcesHealthy,
      List<String> unhealthySources) {}

  /** A condition of the last evaluation with its stored text (DC-TX-03). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Condition(String detail, boolean held) {}
}
