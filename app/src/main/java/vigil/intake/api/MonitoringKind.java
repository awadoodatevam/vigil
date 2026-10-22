package vigil.intake.api;

import java.util.Arrays;
import java.util.Optional;

/** The kinds of monitoring source (DM-E-15 {@code source_kind}, complete list). */
public enum MonitoringKind {
  ZABBIX("zabbix"),
  ALERTMANAGER("alertmanager");

  private final String key;

  MonitoringKind(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The kind with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<MonitoringKind> fromKey(String key) {
    return Arrays.stream(values()).filter(kind -> kind.key.equals(key)).findFirst();
  }
}
