package vigil.reporting.api;

import java.util.List;

/** The content of seed part 17 (DM-R-56 (17)); single values arrive as text. */
public record ReportingPart(List<BaselinePart> baselines) {

  /** A baseline value of the part. */
  public record BaselinePart(String metricKey, String value, String unit, String label) {}
}
