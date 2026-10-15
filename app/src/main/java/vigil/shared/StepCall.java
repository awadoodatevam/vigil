package vigil.shared;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A call to a step handler (AR-R-21). {@code parameters} and {@code stepState} are JSON text
 * ({@code stepState} may be {@code null}); {@code recoveryCheckKey} is set only for Recovery check
 * steps and {@code earlierApprovers} only for Approval steps (DM-A-15).
 */
public record StepCall(
    TenantId tenantId,
    UUID runId,
    UUID incidentId,
    UUID stepExecutionId,
    Key stepId,
    StepType stepType,
    String parameters,
    long evaluationCount,
    String stepState,
    WakeReason wakeReason,
    Key recoveryCheckKey,
    List<UUID> earlierApprovers) {

  /** The step types of DM-S-01 to DM-S-11. */
  public enum StepType {
    TRIGGER,
    CONDITION,
    DIAGNOSTIC,
    AI_TASK,
    APPROVAL,
    ACTION,
    RECOVERY_CHECK,
    ESCALATION,
    NOTIFICATION,
    TICKET,
    WAIT;

    /** The lower-case key of the step type. */
    public String key() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * Why a step is called: {@code start}, {@code timer}, {@code retry}, {@code resume}, {@code
   * restart} or {@code event:{wake event key}} (AR-R-21).
   */
  public record WakeReason(String value) {

    private static final List<String> PLAIN =
        List.of("start", "timer", "retry", "resume", "restart");
    private static final String EVENT_PREFIX = "event:";

    public WakeReason {
      if (value == null || !(PLAIN.contains(value) || isEvent(value))) {
        throw new IllegalArgumentException("invalid wake reason");
      }
    }

    private static boolean isEvent(String value) {
      if (!value.startsWith(EVENT_PREFIX)) {
        return false;
      }
      String key = value.substring(EVENT_PREFIX.length());
      for (Waiting.WakeEvent event : Waiting.WakeEvent.values()) {
        if (event.key().equals(key)) {
          return true;
        }
      }
      return false;
    }
  }

  public StepCall {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(incidentId, "incidentId");
    Objects.requireNonNull(stepExecutionId, "stepExecutionId");
    Objects.requireNonNull(stepId, "stepId");
    Objects.requireNonNull(stepType, "stepType");
    Objects.requireNonNull(parameters, "parameters");
    Objects.requireNonNull(wakeReason, "wakeReason");
    if (recoveryCheckKey != null && stepType != StepType.RECOVERY_CHECK) {
      throw new IllegalArgumentException("recoveryCheckKey is only for recovery_check steps");
    }
    earlierApprovers = earlierApprovers == null ? List.of() : List.copyOf(earlierApprovers);
    if (!earlierApprovers.isEmpty() && stepType != StepType.APPROVAL) {
      throw new IllegalArgumentException("earlierApprovers is only for approval steps");
    }
  }
}
