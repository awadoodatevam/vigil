package vigil.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-30: the timeline, newest first, and the grouping summary (DC-S03-13). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record IncidentTimeline(StableGrouping stableGrouping, List<Entry> entries) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record StableGrouping(
      String stableAt, long alertCount, long mergedGroupCount, String hypothesisName) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Entry(String timelineEntryId, String at, ActorName actor, String entryType, String text) {}
}
