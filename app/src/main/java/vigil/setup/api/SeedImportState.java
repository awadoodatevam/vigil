package vigil.setup.api;

import java.util.Locale;

/** The states of a seed import record (DM-SM-16). */
public enum SeedImportState {
  RUNNING,
  SUCCEEDED,
  FAILED;

  /** The stored key (Example: {@code running}). */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
