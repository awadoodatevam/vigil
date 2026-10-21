package vigil.setup.api;

import java.util.Locale;

/** The outcome of one part of a seed import (DM-E-82). */
public enum PartOutcome {
  SUCCEEDED,
  FAILED,
  NOT_STARTED;

  /** The stored key (Example: {@code not_started}). */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
