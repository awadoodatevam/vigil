package vigil.setup;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** The registered seed parts: one importer per part number of the catalogue (BP-R-29). */
@Component
class SeedPartRegistry {

  private final TreeMap<Integer, SeedPartImporter> importers = new TreeMap<>();

  SeedPartRegistry(List<SeedPartImporter> importers) {
    for (SeedPartImporter importer : importers) {
      int part = importer.part();
      if (part < 1 || part > SeedParts.ALL.size()) {
        throw new IllegalStateException("no part " + part + " in the catalogue");
      }
      if (this.importers.putIfAbsent(part, importer) != null) {
        throw new IllegalStateException("two importers for part " + part);
      }
    }
  }

  /** The importer of {@code part}, or empty when the part is not registered. */
  Optional<SeedPartImporter> importer(int part) {
    return Optional.ofNullable(importers.get(part));
  }

  /** The number of the last registered part in DM-R-56 order (the last part when none is). */
  int lastRegistered() {
    return importers.isEmpty() ? SeedParts.ALL.size() : importers.lastKey();
  }
}
