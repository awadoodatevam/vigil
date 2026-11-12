package vigil.engine.api;

import java.util.Arrays;
import java.util.Optional;

/** The stage of an open incident (DC-TX-01 "Stage", DM-SM-05 note), complete list. */
public enum Stage {
  INVESTIGATION("investigation"),
  RESPONSE("response"),
  RECOVERY("recovery");

  private final String key;

  Stage(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<Stage> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
