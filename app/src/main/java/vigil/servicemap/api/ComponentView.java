package vigil.servicemap.api;

/** A component (DM-E-26); each of the last four fields is {@code null} when empty. */
public record ComponentView(
    String componentKey,
    String name,
    ComponentKind kind,
    Criticality criticality,
    String ownerTeamKey,
    String runsOnKey,
    String targetKey) {}
