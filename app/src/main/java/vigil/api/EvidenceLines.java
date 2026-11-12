package vigil.api;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-24: the incident's evidence entries (DC-S03-08). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record EvidenceLines(List<EvidenceLine> entries) {

  /** One entry; {@code output_values} only for a {@code diagnostic_result} with values. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record EvidenceLine(
      String evidenceEntryId,
      String kind,
      String summary,
      String sourceSystem,
      String collectedAt,
      JsonNode outputValues) {}
}
