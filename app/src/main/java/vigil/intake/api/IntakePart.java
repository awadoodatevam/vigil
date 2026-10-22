package vigil.intake.api;

import java.util.List;

/**
 * The content of seed part 6 (DM-R-56 (6)): the texts of {@code 03-monitoring.yaml} as the seed
 * import reads them, so the import reports every bad value as a problem. Any value may be {@code
 * null}.
 */
public record IntakePart(
    List<SourcePart> sources, List<AlertTypePart> alertTypes, List<MappingPart> mappings) {

  /** A monitoring source of the part; {@code connectorInstance} and its token are handles. */
  public record SourcePart(
      String name,
      String sourceKey,
      String sourceKind,
      String connectorInstance,
      String webhookTokenRef,
      String healthCheckIntervalS,
      String staleAfterS,
      String disconnectedAfterS) {}

  /** An alert type definition of the part. */
  public record AlertTypePart(String alertType, String displayName) {}

  /** An alert type mapping of the part. */
  public record MappingPart(
      String sourceKind,
      String order,
      String field,
      String operator,
      String value,
      String alertType) {}
}
