package vigil.knowledge.api;

import java.util.List;
import java.util.UUID;

/** A runbook (DM-E-63); both lists sorted. */
public record RunbookView(
    UUID id,
    String runbookKey,
    long version,
    String title,
    List<String> applicableAlertTypes,
    List<String> applicableComponentKinds,
    String text,
    boolean active) {}
