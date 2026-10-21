package vigil.servicemap.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The monitoring source kind of an identity key (DM-E-27 {@code source_kind}). */
public enum SourceKind {
  ZABBIX,
  ALERTMANAGER;

  /** The stored key (Example: {@code zabbix}). */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The kind whose key is {@code key}, or empty. */
  public static Optional<SourceKind> fromKey(String key) {
    return Arrays.stream(values()).filter(kind -> kind.key().equals(key)).findFirst();
  }
}
