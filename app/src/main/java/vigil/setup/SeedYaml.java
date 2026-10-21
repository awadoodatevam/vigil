package vigil.setup;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** The one YAML reader of the seed import (AR-T-15): a duplicate key is a read failure. */
final class SeedYaml {

  static final YAMLMapper MAPPER =
      YAMLMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private SeedYaml() {}

  /** The tree of {@code text}; throws {@link JacksonException} when it cannot be read. */
  static JsonNode read(String text) {
    return MAPPER.readTree(text);
  }

  /**
   * The first line of the original message, with {@code (line {l}, column {c})} when the exception
   * has a location.
   */
  static String parserMessage(JacksonException e) {
    String original = e.getOriginalMessage();
    String first = original == null ? "" : original.lines().findFirst().orElse("");
    TokenStreamLocation location = e.getLocation();
    if (location != null && location.getLineNr() > 0 && location.getColumnNr() > 0) {
      return first + " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
    }
    return first;
  }
}
