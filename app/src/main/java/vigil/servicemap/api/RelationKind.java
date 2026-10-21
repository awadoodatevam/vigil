package vigil.servicemap.api;

import java.util.Locale;

/** How two components are related (DM-R-07, DM-E-29 {@code relation}). */
public enum RelationKind {
  SAME_COMPONENT,
  DEPENDS_ON,
  DEPENDED_ON_BY,
  NONE;

  /** The stored key (Example: {@code depends_on}). */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
