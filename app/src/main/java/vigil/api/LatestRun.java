package vigil.api;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-29: the incident's latest live run, or {@code null} (DC-S03-06, 07, 12). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record LatestRun(LiveRun run) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record LiveRun(
      String runId,
      String state,
      long runNumber,
      long runCount,
      String currentStepExecutionId,
      JsonNode content,
      List<TeamName> teamNames,
      List<StepExecution> stepExecutions) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record StepExecution(
      String stepExecutionId,
      String stepId,
      String stepType,
      long visit,
      String state,
      String startedAt,
      String namedOutput,
      String timerDueAt) {}
}
