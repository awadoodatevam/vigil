package vigil.servicemap.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The kind of a component (DM-E-26 {@code kind}). */
public enum ComponentKind {
  SERVER,
  APPLICATION,
  DATABASE,
  QUEUE,
  CHECK;

  /** The stored key (Example: {@code server}). */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The kind whose key is {@code key}, or empty. */
  public static Optional<ComponentKind> fromKey(String key) {
    return Arrays.stream(values()).filter(kind -> kind.key().equals(key)).findFirst();
  }
}
