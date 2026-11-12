package vigil.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-25: the AI investigation section (DC-S03-09). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record InvestigationSection(
    String aiStatus,
    String aiUnavailableReason,
    String aiStatusChangedAt,
    String aiLastRequestGroupId,
    Result result,
    Summary summary) {

  /** The latest accepted AI investigation result (DM-E68-R1). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Result(
      String likelyCause,
      String verdict,
      List<EvidenceUsed> evidenceUsed,
      String uncertainty,
      List<String> runbooksSupplied,
      List<String> runbooksCited,
      String providerKind,
      String modelName,
      String createdAt,
      String origin,
      UserName requestedBy) {}

  /** A referenced evidence entry with its summary. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record EvidenceUsed(String evidenceEntryId, String summary) {}

  /** The latest incident summary (DM-E-69). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Summary(String text, String source) {}
}
