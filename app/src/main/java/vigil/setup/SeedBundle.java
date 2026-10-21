package vigil.setup;

import java.util.Map;

/**
 * A seed bundle file read at upload (AR-B-05): the bytes of each entry by name, the decoded text of
 * each text entry, and the name and version of {@code bundle.yaml}.
 */
record SeedBundle(
    Map<String, byte[]> entries,
    Map<String, String> texts,
    String bundleName,
    String bundleVersion) {

  SeedBundle {
    entries = Map.copyOf(entries);
    texts = Map.copyOf(texts);
  }
}
