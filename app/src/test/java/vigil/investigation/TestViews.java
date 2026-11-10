package vigil.investigation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.DataCategory;
import vigil.incidents.api.EvidenceKind;
import vigil.incidents.api.EvidenceView;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.MembershipView;
import vigil.knowledge.api.RunbookView;
import vigil.shared.Ids;

/** Views for the unit tests of {@code Requests} and {@code Texts}. */
final class TestViews {

  static final Instant FIRED = Instant.parse("2026-11-10T09:15:02.345Z");

  private TestViews() {}

  static IncidentView incident(
      String title,
      long alertCount,
      List<String> alertTypes,
      HypothesisCopy hypothesis,
      List<AffectedServiceCopy> services) {
    Instant now = Instant.now();
    return new IncidentView(
        Ids.newId(),
        1,
        1L,
        title,
        IncidentState.OPEN,
        now,
        now,
        now,
        now,
        alertCount,
        alertTypes,
        List.of("zabbix"),
        hypothesis == null ? null : hypothesis.componentKey(),
        hypothesis,
        hypothesis == null ? null : Ids.newId(),
        services,
        null,
        null,
        List.of(),
        null,
        false,
        0,
        null,
        null,
        AiStatus.NOT_RUN,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        now,
        now);
  }

  static MembershipView member(
      String type, String typeName, String component, String title, Instant firedAt) {
    return new MembershipView(
        Ids.newId(),
        Ids.newId(),
        new AlertCopy(
            Ids.newId(),
            "zabbix",
            "k-" + UUID.randomUUID(),
            type,
            typeName,
            title,
            component,
            null,
            firedAt),
        "{}",
        null,
        Ids.newId(),
        firedAt,
        null);
  }

  static EvidenceView evidence(EvidenceKind kind, String summary, String content) {
    DataCategory category =
        switch (kind) {
          case DIAGNOSTIC_RESULT -> DataCategory.DIAGNOSTIC_OUTPUT;
          case SIMILARITY_MATCH -> DataCategory.INCIDENT_HISTORY;
          default -> DataCategory.INCIDENT_RECORD;
        };
    return new EvidenceView(
        Ids.newId(),
        Ids.newId(),
        kind,
        "m",
        Ids.newId(),
        "s",
        Instant.now(),
        summary,
        content,
        category);
  }

  static RunbookView runbook(String key, String title, String text) {
    return new RunbookView(Ids.newId(), key, 1, title, List.of("disk_full"), List.of(), text, true);
  }
}
