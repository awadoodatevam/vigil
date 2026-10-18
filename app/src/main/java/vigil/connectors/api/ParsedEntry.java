package vigil.connectors.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One entry of a parsed body (DM-E-19, DM-E-20): its index and its exact payload text. */
public sealed interface ParsedEntry permits ParsedEntry.Normalised, ParsedEntry.Failed {

  int entryIndex();

  String payload();

  /**
   * An entry read into its normalised fields; {@code title}, {@code sourceTime} and {@code
   * sourceSeverity} may be {@code null}.
   */
  record Normalised(
      int entryIndex,
      String payload,
      String alertKey,
      AlertStatus status,
      String title,
      Instant sourceTime,
      String sourceSeverity,
      List<IdentityPair> identityPairs,
      Map<String, String> mappingFields)
      implements ParsedEntry {

    public Normalised {
      identityPairs = List.copyOf(identityPairs);
      mappingFields = Collections.unmodifiableMap(new LinkedHashMap<>(mappingFields));
    }
  }

  /** An entry that cannot be normalised, with the reason. */
  record Failed(int entryIndex, String payload, String reason) implements ParsedEntry {}
}
